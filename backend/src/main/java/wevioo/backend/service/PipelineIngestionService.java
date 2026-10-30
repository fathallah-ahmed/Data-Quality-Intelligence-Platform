package wevioo.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wevioo.backend.domain.*;
import wevioo.backend.dto.LiveEvent;
import wevioo.backend.dto.PipelineDtos.*;
import wevioo.backend.repository.*;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class PipelineIngestionService {
    private final PipelineRunRepository pipelineRunRepository;
    private final DatasetVersionRepository versionRepository;
    private final TableVersionRepository tableVersionRepository;
    private final TableAnalysisRepository tableAnalysisRepository;
    private final GlobalAnalysisRepository globalAnalysisRepository;
    private final JsonService jsonService;

    public PipelineIngestionService(
        PipelineRunRepository pipelineRunRepository,
        DatasetVersionRepository versionRepository,
        TableVersionRepository tableVersionRepository,
        TableAnalysisRepository tableAnalysisRepository,
        GlobalAnalysisRepository globalAnalysisRepository,
        JsonService jsonService
    ) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.versionRepository = versionRepository;
        this.tableVersionRepository = tableVersionRepository;
        this.tableAnalysisRepository = tableAnalysisRepository;
        this.globalAnalysisRepository = globalAnalysisRepository;
        this.jsonService = jsonService;
    }

    @Transactional
    public LiveEvent markStarted(String externalRunId) {
        PipelineRun run = requireRun(externalRunId);
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        run.getDatasetVersion().setStatus(RunStatus.RUNNING);
        return event("PIPELINE_STARTED", run, null, "Pipeline started");
    }

    @Transactional
    public LiveEvent saveTableAnalysis(String externalRunId, TableAnalysisRequest request) {
        PipelineRun run = requireRun(externalRunId);
        DatasetVersion version = run.getDatasetVersion();

        TableVersion tableVersion = tableVersionRepository
            .findByDatasetVersionIdAndTableName(version.getId(), request.tableName())
            .orElseGet(() -> {
                TableVersion created = new TableVersion();
                created.setDatasetVersion(version);
                created.setTableName(request.tableName());
                return created;
            });

        String signatureJson = jsonService.toJson(request.dataSignature());
        tableVersion.setDataSignatureJson(signatureJson);

        Optional<TableVersion> previousTableVersion = tableVersionRepository
            .findTopByDatasetVersionDatasetIdAndTableNameAndDatasetVersionIdNotOrderByDatasetVersionVersionNumberDesc(
                version.getDataset().getId(),
                request.tableName(),
                version.getId()
            );
        if (previousTableVersion.isPresent()) {
            TableVersion previous = previousTableVersion.get();
            tableVersion.setPreviousTableVersion(previous);
            tableVersion.setChangeStatus(signatureJson != null && signatureJson.equals(previous.getDataSignatureJson())
                ? ChangeStatus.UNCHANGED
                : ChangeStatus.MODIFIED);
        } else {
            tableVersion.setChangeStatus(ChangeStatus.NEW);
        }

        tableVersion = tableVersionRepository.save(tableVersion);

        TableAnalysis analysis = tableAnalysisRepository.findByTableVersionId(tableVersion.getId())
            .orElseGet(TableAnalysis::new);
        analysis.setTableVersion(tableVersion);
        analysis.setRowCount(request.datasetMetrics() == null ? null : request.datasetMetrics().rowCount());
        analysis.setColumnCount(request.datasetMetrics() == null ? null : request.datasetMetrics().columnCount());
        analysis.setDuplicateCount(request.datasetMetrics() == null ? null : request.datasetMetrics().duplicateRows());
        analysis.setMetricsJsonPath(request.metricsJsonPath());
        analysis.setAnalysisJsonPath(request.analysisJsonPath());
        analysis.setLlmModel(request.llmModelUsed());
        analysis.setLlmInterpretationJson(jsonService.toJson(request.llmInterpretation()));
        analysis.setLlmSummary(jsonService.summaryFromInterpretation(request.llmInterpretation()));
        analysis.setMissingCount(totalMissing(request.columnMetrics()));
        analysis.setQualityScore(qualityScore(request));
        analysis.getColumnMetrics().clear();

        if (request.columnMetrics() != null) {
            for (ColumnMetrics incoming : request.columnMetrics()) {
                ColumnMetric metric = new ColumnMetric();
                metric.setTableAnalysis(analysis);
                metric.setColumnName(incoming.name());
                metric.setDataType(incoming.dataType());
                metric.setMissingCount(incoming.missingCount());
                metric.setMissingPercentage(incoming.missingPercent());
                metric.setUniqueCount(incoming.distinctCount());
                metric.setMinValue(incoming.minValue());
                metric.setMaxValue(incoming.maxValue());
                analysis.getColumnMetrics().add(metric);
            }
        }

        tableVersion.setAnalysis(analysis);
        tableAnalysisRepository.save(analysis);
        return event(
            "TABLE_READY",
            run,
            request.tableName(),
            "Table " + request.tableName() + " analysis is ready"
        );
    }

    @Transactional
    public LiveEvent saveGlobalAnalysis(String externalRunId, GlobalAnalysisRequest request) {
        PipelineRun run = requireRun(externalRunId);
        DatasetVersion version = run.getDatasetVersion();

        GlobalAnalysis global = globalAnalysisRepository.findByDatasetVersionId(version.getId())
            .orElseGet(GlobalAnalysis::new);
        global.setDatasetVersion(version);
        global.setTotalTables(request.datasetCount());
        global.setTotalRows(request.totalRowCount());
        global.setTotalColumns(request.totalColumnCount());
        global.setAnalysisJsonPath(request.analysisJsonPath());
        global.setLlmInterpretationJson(jsonService.toJson(request.llmInterpretation()));
        global.setLlmGlobalSummary(jsonService.summaryFromInterpretation(request.llmInterpretation()));
        global.setRecommendations(jsonService.toJson(request.llmInterpretation() == null ? null : request.llmInterpretation().get("recommendations")));
        global.setGlobalQualityScore(null);
        version.setGlobalAnalysis(global);
        globalAnalysisRepository.save(global);
        return event("GLOBAL_READY", run, null, "Global portfolio analysis is ready");
    }

    @Transactional
    public LiveEvent markCompleted(String externalRunId, PipelineCompletedRequest request) {
        PipelineRun run = requireRun(externalRunId);
        RunStatus status = parseStatus(request.status());
        run.setStatus(status);
        run.setFinishedAt(Instant.now());
        run.setErrorMessage(request.errorMessage());
        run.getDatasetVersion().setStatus(status);
        String type = status == RunStatus.FAILED ? "PIPELINE_FAILED" : "PIPELINE_COMPLETED";
        return event(type, run, null, status == RunStatus.FAILED ? "Pipeline failed" : "Pipeline completed");
    }

    private LiveEvent event(String type, PipelineRun run, String tableName, String message) {
        DatasetVersion version = run.getDatasetVersion();
        return new LiveEvent(
            type,
            version.getDataset().getId(),
            version.getId(),
            version.getVersionNumber(),
            run.getExternalRunId(),
            run.getStatus().name(),
            tableName,
            message,
            Instant.now()
        );
    }

    private PipelineRun requireRun(String externalRunId) {
        return pipelineRunRepository.findByExternalRunId(externalRunId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown pipeline run: " + externalRunId));
    }

    private RunStatus parseStatus(String status) {
        if (status == null) {
            return RunStatus.COMPLETED;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "failed", "error" -> RunStatus.FAILED;
            case "running" -> RunStatus.RUNNING;
            default -> RunStatus.COMPLETED;
        };
    }

    private Long totalMissing(List<ColumnMetrics> columns) {
        if (columns == null) {
            return null;
        }
        return columns.stream()
            .map(ColumnMetrics::missingCount)
            .filter(value -> value != null)
            .reduce(0L, Long::sum);
    }

    private Double qualityScore(TableAnalysisRequest request) {
        if (request.columnMetrics() == null || request.columnMetrics().isEmpty()) {
            return null;
        }
        double avgMissing = request.columnMetrics().stream()
            .map(ColumnMetrics::missingPercent)
            .filter(value -> value != null)
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0);
        double duplicatePenalty = request.datasetMetrics() == null || request.datasetMetrics().duplicatePercent() == null
            ? 0
            : request.datasetMetrics().duplicatePercent();
        return Math.max(0, Math.min(100, 100 - avgMissing - duplicatePenalty));
    }
}
