package wevioo.backend.dto;

import java.util.List;
import java.util.Map;

public class PipelineDtos {
    public record FailedTable(
        String tableName,
        String error
    ) {}

    public record PipelineCompletedRequest(
        String pipelineRunId,
        String status,
        String errorMessage,
        List<FailedTable> failedTables
    ) {}

    public record DatasetMetrics(
        Long rowCount,
        Integer columnCount,
        Long duplicateRows,
        Double duplicatePercent
    ) {}

    public record ColumnMetrics(
        String name,
        String dataType,
        Long missingCount,
        Double missingPercent,
        Long distinctCount,
        Double minValue,
        Double maxValue
    ) {}

    public record TableAnalysisRequest(
        String datasetId,
        String versionId,
        String pipelineRunId,
        String tableName,
        DatasetMetrics datasetMetrics,
        List<ColumnMetrics> columnMetrics,
        Map<String, Object> llmInterpretation,
        String llmModelUsed,
        String llmStatus,
        String metricsJsonPath,
        String analysisJsonPath,
        Map<String, Object> dataSignature,
        List<Map<String, Object>> relationships,
        String changeStatus,
        String status
    ) {}

    public record GlobalAnalysisRequest(
        String pipelineRunId,
        Integer datasetCount,
        Long totalRowCount,
        Long totalColumnCount,
        Map<String, Object> llmInterpretation,
        String llmStatus,
        String analysisJsonPath
    ) {}
}
