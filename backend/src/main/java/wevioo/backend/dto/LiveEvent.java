package wevioo.backend.dto;

import java.time.Instant;

public record LiveEvent(
    String type,
    Long datasetId,
    Long versionId,
    Integer versionNumber,
    String pipelineRunId,
    String status,
    String tableName,
    String message,
    Instant emittedAt
) {
    public static LiveEvent connected(String username) {
        return new LiveEvent(
            "CONNECTED",
            null,
            null,
            null,
            null,
            null,
            null,
            "SSE stream connected for " + username,
            Instant.now()
        );
    }
}
