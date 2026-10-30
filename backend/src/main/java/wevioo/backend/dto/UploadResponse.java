package wevioo.backend.dto;

public record UploadResponse(
    Long datasetId,
    Long versionId,
    Integer versionNumber,
    String pipelineRunId,
    String status,
    String filePath,
    Integer fileCount,
    boolean airflowTriggered,
    String airflowMessage,
    boolean duplicateVersion,
    boolean rerunStarted,
    String duplicateMessage
) {}
