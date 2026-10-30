package wevioo.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import wevioo.backend.domain.Dataset;
import wevioo.backend.domain.DatasetVersion;
import wevioo.backend.domain.PipelineRun;
import wevioo.backend.domain.RunStatus;
import wevioo.backend.repository.DatasetRepository;
import wevioo.backend.repository.DatasetVersionRepository;
import wevioo.backend.repository.PipelineRunRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:watchdog;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
    "app.watchdog-interval-ms=3600000",
    "app.watchdog-stale-after-minutes=10",
    "app.watchdog-max-run-minutes=180"
})
@Transactional
class PipelineWatchdogTest {
    @Autowired PipelineWatchdog watchdog;
    @Autowired DatasetRepository datasetRepository;
    @Autowired DatasetVersionRepository versionRepository;
    @Autowired PipelineRunRepository runRepository;
    @MockitoBean AirflowClient airflowClient;

    @Test
    void staleRunUnknownToAirflowIsMarkedFailed() {
        PipelineRun run = pendingRun();
        when(airflowClient.getDagRunState(anyString())).thenReturn(AirflowClient.NOT_FOUND);

        // Fresh runs are left alone.
        assertThat(watchdog.reconcile(Instant.now())).isEmpty();
        // After the stale delay the run is checked against Airflow.
        var events = watchdog.reconcile(Instant.now().plus(Duration.ofMinutes(11)));
        assertThat(events).hasSize(1);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getDatasetVersion().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("no record");
    }

    @Test
    void runningInAirflowIsLeftAloneUntilTheHardTimeout() {
        PipelineRun run = pendingRun();
        when(airflowClient.getDagRunState(anyString())).thenReturn("running");
        assertThat(watchdog.reconcile(Instant.now().plus(Duration.ofMinutes(30)))).isEmpty();
        assertThat(watchdog.reconcile(Instant.now().plus(Duration.ofMinutes(200)))).hasSize(1);
        assertThat(run.getErrorMessage()).contains("exceeded 180 minutes");
    }

    private PipelineRun pendingRun() {
        runRepository.findByStatusIn(java.util.List.of(RunStatus.PENDING, RunStatus.RUNNING))
            .forEach(existing -> existing.setStatus(RunStatus.COMPLETED));
        Dataset dataset = new Dataset();
        dataset.setName("watchdog-" + UUID.randomUUID());
        dataset = datasetRepository.save(dataset);
        DatasetVersion version = new DatasetVersion();
        version.setDataset(dataset);
        version.setVersionNumber(1);
        version.setFilePath("/tmp/none");
        version = versionRepository.save(version);
        PipelineRun run = new PipelineRun();
        run.setExternalRunId("run-" + UUID.randomUUID());
        run.setDatasetVersion(version);
        return runRepository.save(run);
    }
}
