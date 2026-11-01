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
        Double maxValue,
        /** What the column is for, e.g. EMAIL or IDENTIFIER (null on versions analysed before roles existed). */
        String role,
        Double roleConfidence,
        /** The clues that led to the role. */
        List<String> roleEvidence,
        /** rules, or llm when the model settled a column the clues left unclear. */
        String roleSource
    ) {}

    /**
     * One quality dimension of a version, with how it was computed.
     *
     * @param percent     0..100, or null when nothing could be measured (see {@code basis})
     * @param numerator   the values that meet the dimension
     * @param denominator the values that were checked
     * @param definition  what the dimension measures, in words
     * @param basis       which columns or rules it is based on, or why it could not be measured
     */
    public record QualityDimension(
        String key,
        String label,
        Double percent,
        Long numerator,
        Long denominator,
        String definition,
        String basis
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
        RuleDtos.RuleSummary ruleSummary,
        /** Completeness, uniqueness, validity and consistency, each with the figures behind it. */
        List<QualityDimension> dimensions
    ) {}
}
