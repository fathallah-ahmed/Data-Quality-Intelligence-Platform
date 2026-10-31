package wevioo.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class DatasetDtos {
    public record DatasetSummary(
        Long id,
        String name,
        String sourceType,
        Instant createdAt,
        Instant updatedAt,
        Integer versionCount,
        /** Quality rules that failed in the latest version (0 when there are none). */
        Integer failedRules,
        VersionSummary latestVersion
    ) {}

    public record VersionSummary(
        Long id,
        Integer versionNumber,
        String status,
        String filePath,
        Integer expectedTableCount,
        Instant uploadedAt,
        String pipelineRunId,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
    ) {}

    public record ColumnMetricView(
        String columnName,
        String dataType,
        Long missingCount,
        Double missingPercentage,
        Long uniqueCount,
        Double minValue,
        Double maxValue
    ) {}

    public record TableAnalysisView(
        Long tableVersionId,
        String tableName,
        String changeStatus,
        Long rowCount,
        Integer columnCount,
        Long missingCount,
        Long duplicateCount,
        Double qualityScore,
        String llmSummary,
        String llmStatus,
        String llmModel,
        Map<String, Object> llmInterpretation,
        Map<String, Object> dataSignature,
        Map<String, Object> rawMetrics,
        Map<String, Object> rawAnalysis,
        List<ColumnMetricView> columns,
        /** Relationship candidates found by the pipeline from value overlap (source = this table). */
        List<Map<String, Object>> relationships
    ) {}

    public record GlobalAnalysisView(
        Long id,
        Integer totalTables,
        Long totalRows,
        Long totalColumns,
        String llmGlobalSummary,
        String llmStatus,
        Map<String, Object> llmInterpretation,
        String recommendations,
        Map<String, Object> rawGlobalAnalysis
    ) {}

    public record FailedTableView(
        String tableName,
        String error
    ) {}

    /**
     * @param qualityScore row-weighted average of the table quality scores (null until a table is ready)
     */
    public record VersionDetails(
        VersionSummary version,
        GlobalAnalysisView globalAnalysis,
        List<TableAnalysisView> tables,
        List<FailedTableView> failedTables,
        Double qualityScore,
        /** Null until the quality rules have been evaluated for this version. */
        RuleDtos.RuleSummary ruleSummary
    ) {}
}
