package wevioo.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import wevioo.backend.domain.DatasetVersion;
import wevioo.backend.domain.PipelineRun;
import wevioo.backend.domain.RunStatus;
import wevioo.backend.dto.LiveEvent;
import wevioo.backend.repository.PipelineRunRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks up the runs that were in flight when the backend stopped.
 *
 * <p>A run can be left PENDING or RUNNING by a restart in two ways: the backend died before it
 * called Airflow (Airflow has never heard of the run), or Airflow has been reset or the run died
 * meanwhile. For each of them this asks Airflow and
 * <ul>
 *   <li>starts the run again when Airflow does not know it and its files are still on disk,</li>
 *   <li>fails it with a clear message when Airflow reports it as failed or the files are gone,</li>
 *   <li>leaves it alone when Airflow is still working on it or cannot be reached: the pipeline
 *       keeps retrying its callbacks, and the watchdog deals with it if nothing arrives.</li>
 * </ul>
 */
@Component
public class RunRecovery {
    private static final Logger log = LoggerFactory.getLogger(RunRecovery.class);

    private final PipelineRunRepository runRepository;
    private final AirflowClient airflowClient;
    private final EventStreamService eventStreamService;
    private final TransactionTemplate transactionTemplate;

    public RunRecovery(
        PipelineRunRepository runRepository,
        AirflowClient airflowClient,
        EventStreamService eventStreamService,
        PlatformTransactionManager transactionManager
    ) {
        this.runRepository = runRepository;
        this.airflowClient = airflowClient;
        this.eventStreamService = eventStreamService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Runs in the background so a slow or unreachable Airflow never delays the startup. */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        try {
            recover();
        } catch (RuntimeException e) {
            log.warn("Could not recover the runs that were in flight: {}", e.getMessage());
        }
    }

    /** @return the number of runs that were restarted or failed */
    public int recover() {
        List<LiveEvent> events = new ArrayList<>();
        Integer handled = transactionTemplate.execute(status -> {
            int count = 0;
            for (PipelineRun run : runRepository.findByStatusIn(List.of(RunStatus.PENDING, RunStatus.RUNNING))) {
                if (recoverRun(run, events)) {
                    count++;
                }
            }
            return count;
        });
        events.forEach(eventStreamService::publish);
        return handled == null ? 0 : handled;
    }

    private boolean recoverRun(PipelineRun run, List<LiveEvent> events) {
        DatasetVersion version = run.getDatasetVersion();
        String runId = run.getExternalRunId();
        String state = airflowClient.getDagRunState(runId);

        if (AirflowClient.NOT_FOUND.equals(state)) {
            Path folder = version.getFilePath() == null ? null : Path.of(version.getFilePath());
            if (folder == null || !Files.isDirectory(folder)) {
                return fail(run, events, "The backend restarted before this run reached Airflow and its uploaded files are gone. Upload the files again.");
            }
            AirflowClient.TriggerResult trigger = airflowClient.triggerPipeline(
                runId,
                String.valueOf(version.getDataset().getId()),
                String.valueOf(version.getVersionNumber()),
                PipelinePaths.toContainerPath(folder)
            );
            if (trigger.triggered()) {
                log.info("Run {} was unknown to Airflow after a restart: started it again", runId);
                run.setStatus(RunStatus.PENDING);
                run.setStartedAt(null);
                version.setStatus(RunStatus.PENDING);
                events.add(event("PIPELINE_STARTED", run, "The run was started again after a restart"));
                return true;
            }
            return fail(run, events, "The backend restarted during this run and Airflow could not start it again. " + trigger.message());
        }
        if ("failed".equals(state)) {
            return fail(run, events, "Airflow reports this run as failed. Open the Airflow UI to see which task failed.");
        }
        // queued / running / success / unreachable: nothing to change now (see class comment).
        return false;
    }

    private boolean fail(PipelineRun run, List<LiveEvent> events, String message) {
        log.warn("Marking pipeline run {} as FAILED after a restart: {}", run.getExternalRunId(), message);
        run.setStatus(RunStatus.FAILED);
        run.setFinishedAt(Instant.now());
        run.setErrorMessage(message);
        run.getDatasetVersion().setStatus(RunStatus.FAILED);
        events.add(event("PIPELINE_FAILED", run, message));
        return true;
    }

    private LiveEvent event(String type, PipelineRun run, String message) {
        DatasetVersion version = run.getDatasetVersion();
        return new LiveEvent(
            type,
            version.getDataset().getId(),
            version.getId(),
            version.getVersionNumber(),
            run.getExternalRunId(),
            run.getStatus().name(),
            null,
            message,
            Instant.now()
        );
    }
}
