package wevioo.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import wevioo.backend.config.AppProperties;
import wevioo.backend.domain.DatasetVersion;
import wevioo.backend.domain.PipelineRun;
import wevioo.backend.domain.RunStatus;
import wevioo.backend.dto.LiveEvent;
import wevioo.backend.repository.PipelineRunRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds runs stuck in PENDING/RUNNING and reconciles them with Airflow, so a version never
 * stays "running" forever when Airflow is down, the DAG is paused or a callback was lost.
 */
@Component
public class PipelineWatchdog {
    private static final Logger log = LoggerFactory.getLogger(PipelineWatchdog.class);

    private final PipelineRunRepository runRepository;
    private final AirflowClient airflowClient;
    private final EventStreamService eventStreamService;
    private final AppProperties properties;
    private final TransactionTemplate transactionTemplate;

    public PipelineWatchdog(
        PipelineRunRepository runRepository,
        AirflowClient airflowClient,
        EventStreamService eventStreamService,
        AppProperties properties,
        PlatformTransactionManager transactionManager
    ) {
        this.runRepository = runRepository;
        this.airflowClient = airflowClient;
        this.eventStreamService = eventStreamService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(
        fixedDelayString = "${app.watchdog-interval-ms:60000}",
        initialDelayString = "${app.watchdog-interval-ms:60000}"
    )
    public void checkStaleRuns() {
        List<LiveEvent> events = transactionTemplate.execute(status -> reconcile(Instant.now()));
        if (events != null) {
            events.forEach(eventStreamService::publish);
        }
    }

    List<LiveEvent> reconcile(Instant now) {
        List<LiveEvent> events = new ArrayList<>();
        Duration staleAfter = Duration.ofMinutes(properties.getWatchdogStaleAfterMinutes());
        Duration maxRun = Duration.ofMinutes(properties.getWatchdogMaxRunMinutes());

        for (PipelineRun run : runRepository.findByStatusIn(List.of(RunStatus.PENDING, RunStatus.RUNNING))) {
            Instant since = run.getStartedAt() != null ? run.getStartedAt()
                : run.getCreatedAt() != null ? run.getCreatedAt()
                : run.getDatasetVersion().getUploadedAt();
            Duration age = Duration.between(since, now);
            if (age.compareTo(staleAfter) < 0) {
                continue;
            }

            String state = airflowClient.getDagRunState(run.getExternalRunId());
            String failure = switch (state) {
                case "failed" -> "Airflow reports this run as failed. Open the Airflow UI to see which task failed.";
                case AirflowClient.NOT_FOUND -> "Airflow has no record of this run (it was never started or was deleted).";
                case "success" -> "Airflow finished this run but the backend never received the completion callback. Check PIPELINE_API_TOKEN and BACKEND_URL.";
                default -> age.compareTo(maxRun) >= 0
                    ? "The run exceeded " + maxRun.toMinutes() + " minutes without finishing (Airflow state: " + state + ")."
                    : null;
            };
            if (failure == null) {
                continue;
            }

            log.warn("Marking pipeline run {} as FAILED: {}", run.getExternalRunId(), failure);
            run.setStatus(RunStatus.FAILED);
            run.setFinishedAt(now);
            run.setErrorMessage(failure);
            DatasetVersion version = run.getDatasetVersion();
            version.setStatus(RunStatus.FAILED);
            events.add(new LiveEvent(
                "PIPELINE_FAILED",
                version.getDataset().getId(),
                version.getId(),
                version.getVersionNumber(),
                run.getExternalRunId(),
                RunStatus.FAILED.name(),
                null,
                failure,
                now
            ));
        }
        return events;
    }
}
