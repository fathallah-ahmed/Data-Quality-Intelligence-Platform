package wevioo.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wevioo.backend.domain.*;
import wevioo.backend.dto.LiveEvent;
import wevioo.backend.dto.PipelineDtos.*;
import wevioo.backend.exception.NotFoundException;
import wevioo.backend.repository.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

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

        tableVersion.setDataSignatureJson(jsonService.toJson(request.dataSignature()));

        Optional<TableVersion> previousTableVersion = tableVersionRepository
            .findTopByDatasetVersionDatasetIdAndTableNameAndDatasetVersionVersionNumberLessThanOrderByDatasetVersionVersionNumberDesc(
                version.getDataset().getId(),
                request.tableName(),
                version.getVersionNumber()
            )
            .filter(previous -> previous.getChangeStatus() != ChangeStatus.REMOVED);
        if (previousTableVersion.isPresent()) {
            TableVersion previous = previousTableVersion.get();
            tableVersion.setPreviousTableVersion(previous);
            boolean same = DataSignatures.sameContent(request.dataSignature(), jsonService.toMap(previous.getDataSignatureJson()));
            tableVersion.setChangeStatus(same ? ChangeStatus.UNCHANGED : ChangeStatus.MODIFIED);
        } else {
            tableVersion.setPreviousTableVersion(null);
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
        analysis.setLlmStatus(request.llmStatus());
        analysis.setRelationshipsJson(request.relationships() == null || request.relationships().isEmpty()
            ? null
            : jsonService.toJson(request.relationships()));
        analysis.setLlmInterpretationJson(jsonService.toJson(request.llmInterpretation()));
        analysis.setLlmSummary(jsonService.summaryFromInterpretation(request.llmInterpretation()));
        analysis.setCrossChecksJson(request.crossChecks() == null || request.crossChecks().isEmpty()
            ? null
            : jsonService.toJson(request.crossChecks()));
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
                metric.setFormatStatsJson(incoming.formatStats() == null || incoming.formatStats().isEmpty()
                    ? null
                    : jsonService.toJson(incoming.formatStats()));
                metric.setRole(incoming.role());
                metric.setRoleConfidence(incoming.roleConfidence());
                metric.setRoleSource(incoming.roleSource());
                metric.setRoleEvidenceJson(incoming.roleEvidence() == null || incoming.roleEvidence().isEmpty()
                    ? null
                    : jsonService.toJson(incoming.roleEvidence()));
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
        global.setLlmStatus(request.llmStatus());
        global.setLlmInterpretationJson(jsonService.toJson(request.llmInterpretation()));
        global.setLlmGlobalSummary(jsonService.summaryFromInterpretation(request.llmInterpretation()));
        global.setRecommendations(jsonService.toJson(request.llmInterpretation() == null ? null : request.llmInterpretation().get("recommendations")));
        global.setGlobalQualityScore(versionQualityScore(version));
        version.setGlobalAnalysis(global);
        globalAnalysisRepository.save(global);
        return event("GLOBAL_READY", run, null, "Global portfolio analysis is ready");
    }

    @Transactional
    public LiveEvent markCompleted(String externalRunId, PipelineCompletedRequest request) {
        PipelineRun run = requireRun(externalRunId);
        RunStatus status = parseStatus(request.status());
        List<FailedTable> failedTables = request.failedTables() == null ? List.of() : request.failedTables();
        run.setStatus(status);
        run.setFinishedAt(Instant.now());
        run.setErrorMessage(errorMessage(status, request.errorMessage(), failedTables));
        run.setFailedTablesJson(failedTables.isEmpty() ? null : jsonService.toJson(failedTables));
        run.getDatasetVersion().setStatus(status);
        if (status != RunStatus.FAILED) {
            markRemovedTables(run.getDatasetVersion());
        }
        return switch (status) {
            case FAILED -> event("PIPELINE_FAILED", run, null, "Pipeline failed");
            case PARTIAL -> event("PIPELINE_COMPLETED", run, null, "Pipeline completed with " + failedTables.size() + " failed table(s)");
            default -> event("PIPELINE_COMPLETED", run, null, "Pipeline completed");
        };
    }

    /** Record tables of the previous version that are absent from this one as REMOVED. */
    private void markRemovedTables(DatasetVersion version) {
        Optional<DatasetVersion> previous = versionRepository
            .findTopByDatasetIdAndVersionNumberLessThanOrderByVersionNumberDesc(version.getDataset().getId(), version.getVersionNumber());
        if (previous.isEmpty()) {
            return;
        }
        Set<String> currentNames = tableVersionRepository.findByDatasetVersionIdOrderByTableNameAsc(version.getId()).stream()
            .map(TableVersion::getTableName)
            .collect(Collectors.toSet());
        for (TableVersion previousTable : tableVersionRepository.findByDatasetVersionIdOrderByTableNameAsc(previous.get().getId())) {
            if (previousTable.getChangeStatus() == ChangeStatus.REMOVED || currentNames.contains(previousTable.getTableName())) {
                continue;
            }
            TableVersion removed = new TableVersion();
            removed.setDatasetVersion(version);
            removed.setTableName(previousTable.getTableName());
            removed.setChangeStatus(ChangeStatus.REMOVED);
            removed.setPreviousTableVersion(previousTable);
            removed.setDataSignatureJson(previousTable.getDataSignatureJson());
            tableVersionRepository.save(removed);
        }
    }

    private Double versionQualityScore(DatasetVersion version) {
        double weighted = 0;
        double weight = 0;
        double plain = 0;
        int scored = 0;
        for (TableVersion table : tableVersionRepository.findByDatasetVersionIdOrderByTableNameAsc(version.getId())) {
            TableAnalysis analysis = table.getAnalysis();
            if (analysis == null || analysis.getQualityScore() == null) {
                continue;
            }
            long rows = analysis.getRowCount() == null ? 0 : analysis.getRowCount();
            weighted += analysis.getQualityScore() * rows;
            weight += rows;
            plain += analysis.getQualityScore();
            scored++;
        }
        if (scored == 0) {
            return null;
        }
        return weight > 0 ? weighted / weight : plain / scored;
    }

    private String errorMessage(RunStatus status, String reported, List<FailedTable> failedTables) {
        if (status == RunStatus.COMPLETED) {
            return null;
        }
        if (reported != null && !reported.isBlank() && !reported.trim().startsWith("[")) {
            return reported;
        }
        if (!failedTables.isEmpty()) {
            return failedTables.size() + " table(s) could not be analysed: " + failedTables.stream()
                .map(table -> table.tableName() + " (" + table.error() + ")")
                .collect(Collectors.joining("; "));
        }
        return status == RunStatus.FAILED ? "The pipeline failed. Check the Airflow task logs for details." : null;
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
            .orElseThrow(() -> new NotFoundException("Unknown pipeline run: " + externalRunId));
    }

    /** Maps the pipeline's status vocabulary (success / partial / failed) to {@link RunStatus}. */
    static RunStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return RunStatus.COMPLETED;
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "failed", "error" -> RunStatus.FAILED;
            case "partial" -> RunStatus.PARTIAL;
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
            .filter(Objects::nonNull)
            .reduce(0L, Long::sum);
    }

    /** 100 minus the average missing % across columns minus the duplicate-row %, clamped to 0..100. */
    private Double qualityScore(TableAnalysisRequest request) {
        if (request.columnMetrics() == null || request.columnMetrics().isEmpty()) {
            return null;
        }
        if (request.datasetMetrics() != null && Long.valueOf(0).equals(request.datasetMetrics().rowCount())) {
            return null;
        }
        double avgMissing = request.columnMetrics().stream()
            .map(ColumnMetrics::missingPercent)
            .filter(Objects::nonNull)
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0);
        double duplicatePenalty = request.datasetMetrics() == null || request.datasetMetrics().duplicatePercent() == null
            ? 0
            : request.datasetMetrics().duplicatePercent();
        return Math.max(0, Math.min(100, 100 - avgMissing - duplicatePenalty));
    }
}
