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
        VersionSummary latestVersion
    ) {}

    public record VersionSummary(
        Long id,
        Integer versionNumber,
        String status,
        String filePath,
        Integer expectedTableCount,
        Instant uploadedAt,
        String pipelineRunId
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
        Map<String, Object> llmInterpretation,
        Map<String, Object> dataSignature,
        Map<String, Object> rawMetrics,
        Map<String, Object> rawAnalysis,
        List<ColumnMetricView> columns
    ) {}

    public record GlobalAnalysisView(
        Long id,
        Integer totalTables,
        Long totalRows,
        Long totalColumns,
        Double globalQualityScore,
        String llmGlobalSummary,
        Map<String, Object> llmInterpretation,
        String recommendations,
        Map<String, Object> rawGlobalAnalysis
    ) {}

    public record VersionDetails(
        VersionSummary version,
        GlobalAnalysisView globalAnalysis,
        List<TableAnalysisView> tables
    ) {}
}
