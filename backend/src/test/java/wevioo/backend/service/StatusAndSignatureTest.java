package wevioo.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import wevioo.backend.controller.ApiExceptionHandler;
import wevioo.backend.domain.RunStatus;
import wevioo.backend.dto.DatasetDtos.TableAnalysisView;
import wevioo.backend.exception.NotFoundException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StatusAndSignatureTest {
    @Test
    void pipelineStatusesMapToRunStatus() {
        assertThat(PipelineIngestionService.parseStatus("success")).isEqualTo(RunStatus.COMPLETED);
        assertThat(PipelineIngestionService.parseStatus("partial")).isEqualTo(RunStatus.PARTIAL);
        assertThat(PipelineIngestionService.parseStatus("PARTIAL")).isEqualTo(RunStatus.PARTIAL);
        assertThat(PipelineIngestionService.parseStatus("failed")).isEqualTo(RunStatus.FAILED);
        assertThat(PipelineIngestionService.parseStatus("error")).isEqualTo(RunStatus.FAILED);
        assertThat(PipelineIngestionService.parseStatus(null)).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void contentHashDetectsValueEdits() {
        Map<String, Object> v1 = Map.of("row_count", 2, "content_hash", "aaa", "schema_hash", "s");
        Map<String, Object> v2 = Map.of("row_count", 2, "content_hash", "bbb", "schema_hash", "s");
        assertThat(DataSignatures.sameContent(v1, v1)).isTrue();
        assertThat(DataSignatures.sameContent(v2, v1)).isFalse();
    }

    @Test
    void legacySignaturesFallBackToCounts() {
        Map<String, Object> legacy = Map.of("row_count", 2, "column_count", 1, "duplicate_rows", 0, "columns", List.of(Map.of("name", "a")));
        Map<String, Object> current = Map.of("row_count", 2L, "column_count", 1L, "duplicate_rows", 0L,
            "columns", List.of(Map.of("name", "a")), "content_hash", "x");
        assertThat(DataSignatures.sameContent(current, legacy)).isTrue();
        assertThat(DataSignatures.sameContent(Map.of("row_count", 3), legacy)).isFalse();
    }

    @Test
    void sha256IsStreamed() throws Exception {
        String hash = DatasetService.sha256(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        assertThat(hash).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void exceptionHandlerStatusCodes() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        assertThat(handler.uploadTooLarge(new MaxUploadSizeExceededException(10)).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(handler.notFound(new NotFoundException("Dataset not found")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var serverError = handler.otherErrors(new RuntimeException("secret internal detail"));
        assertThat(serverError.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(serverError.getBody().get("error")).doesNotContain("secret");
    }

    @Test
    void rowWeightedQualityScore() {
        TableAnalysisView big = table("big", 900L, 90.0, "NEW");
        TableAnalysisView small = table("small", 100L, 50.0, "NEW");
        TableAnalysisView removed = table("old", 1000L, 0.0, "REMOVED");
        assertThat(QualityScores.rowWeighted(List.of(big, small, removed))).isEqualTo(86.0);
        assertThat(QualityScores.rowWeighted(List.of())).isNull();
    }

    private TableAnalysisView table(String name, Long rows, Double score, String change) {
        return new TableAnalysisView(1L, name, change, rows, 1, 0L, 0L, score, null, null, null, Map.of(), Map.of(), Map.of(), Map.of(), List.of());
    }
}
