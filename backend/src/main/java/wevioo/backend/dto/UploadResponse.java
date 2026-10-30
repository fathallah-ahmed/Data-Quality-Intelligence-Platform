package wevioo.backend.dto;

import java.util.List;

/**
 * @param skippedFiles uploaded files that were ignored (empty files), with the reason
 */
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
    String duplicateMessage,
    List<String> skippedFiles
) {}
