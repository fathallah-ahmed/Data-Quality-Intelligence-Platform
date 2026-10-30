package wevioo.backend.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import wevioo.backend.repository.DatasetVersionRepository;
import wevioo.backend.service.AirflowClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:api-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.admin-username=admin",
    "app.admin-password=test-password",
    "app.pipeline-api-token=test-pipeline-token",
    "app.watchdog-interval-ms=3600000"
})
@AutoConfigureMockMvc
class ApiIntegrationTest {
    private static final String PIPELINE_TOKEN = "test-pipeline-token";

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.storage-root", () -> storageRoot.toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    DatasetVersionRepository versionRepository;

    @MockitoBean
    AirflowClient airflowClient;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        when(airflowClient.triggerPipeline(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new AirflowClient.TriggerResult(true, "Airflow accepted the run (200)"));
        token = login("admin", "test-password");
    }

    // ------------------------------------------------------------------ auth and status codes

    @Test
    void loginReturnsTokenAndRejectsWrongPassword() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"nope\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Invalid username or password"));

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("admin"))
            .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    @Test
    void apiRequiresAToken() throws Exception {
        mvc.perform(get("/api/datasets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/datasets").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownRouteAndMissingResourcesReturn404() throws Exception {
        mvc.perform(get("/api/does-not-exist").header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/datasets/987654/versions").header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Dataset not found"));
        mvc.perform(get("/api/datasets/987654/versions/1").header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Dataset version not found"));
    }

    @Test
    void pipelineCallbacksCheckTheToken() throws Exception {
        mvc.perform(post("/api/pipeline/run-x/start").header("X-Pipeline-Token", "wrong"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Invalid pipeline token"));
        mvc.perform(post("/api/pipeline/run-x/start"))
            .andExpect(status().isUnauthorized());
        // Valid token but unknown run: 404, which the pipeline retries while the upload commits.
        mvc.perform(post("/api/pipeline/run-unknown/start").header("X-Pipeline-Token", PIPELINE_TOKEN))
            .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ uploads

    @Test
    void duplicateDetectionUsesContentHashNotJustSize() throws Exception {
        String name = "dup-" + UUID.randomUUID();
        JsonNode first = upload(name, false, csv("orders.csv", "id,amount\n1,10\n2,20\n"), csv("customers.csv", "id,name\n1,Ann\n"));
        assertThat(first.get("versionNumber").asInt()).isEqualTo(1);
        assertThat(first.get("duplicateVersion").asBoolean()).isFalse();
        assertThat(first.get("airflowTriggered").asBoolean()).isTrue();

        JsonNode same = upload(name, false, csv("customers.csv", "id,name\n1,Ann\n"), csv("orders.csv", "id,amount\n1,10\n2,20\n"));
        assertThat(same.get("duplicateVersion").asBoolean()).isTrue();
        assertThat(same.get("rerunStarted").asBoolean()).isFalse();
        assertThat(same.get("versionNumber").asInt()).isEqualTo(1);

        // Same file names and byte sizes, one value changed: must be a new version.
        JsonNode edited = upload(name, false, csv("orders.csv", "id,amount\n1,10\n2,30\n"), csv("customers.csv", "id,name\n1,Ann\n"));
        assertThat(edited.get("duplicateVersion").asBoolean()).isFalse();
        assertThat(edited.get("versionNumber").asInt()).isEqualTo(2);
        verify(airflowClient, times(2)).triggerPipeline(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rerunOfTrueDuplicateReusesTheExistingVersion() throws Exception {
        String name = "rerun-" + UUID.randomUUID();
        JsonNode first = upload(name, false, csv("t.csv", "a,b\n1,2\n"));
        completeRun(first.get("pipelineRunId").asString(), "success", null);

        JsonNode rerun = upload(name, true, csv("t.csv", "a,b\n1,2\n"));
        assertThat(rerun.get("duplicateVersion").asBoolean()).isTrue();
        assertThat(rerun.get("rerunStarted").asBoolean()).isTrue();
        assertThat(rerun.get("versionId").asLong()).isEqualTo(first.get("versionId").asLong());
        assertThat(rerun.get("pipelineRunId").asString()).isNotEqualTo(first.get("pipelineRunId").asString());
    }

    @Test
    void legacyVersionsWithoutHashAreComparedByRecomputingTheHash() throws Exception {
        String name = "legacy-" + UUID.randomUUID();
        JsonNode first = upload(name, false, csv("t.csv", "a,b\n1,2\n"));
        // Simulate a version stored before hashing existed: name + size only.
        stripHashesFromSignature(first.get("versionId").asLong());

        JsonNode same = upload(name, false, csv("t.csv", "a,b\n1,2\n"));
        assertThat(same.get("duplicateVersion").asBoolean()).isTrue();
        JsonNode changed = upload(name, false, csv("t.csv", "a,b\n1,3\n"));
        assertThat(changed.get("duplicateVersion").asBoolean()).isFalse();
    }

    @Test
    void invalidFilesAreRejectedBeforeAnythingIsWritten() throws Exception {
        String name = "invalid-" + UUID.randomUUID();
        long before = countFiles();
        mvc.perform(multipart("/api/datasets/upload")
                .file(csv("good.csv", "a\n1\n"))
                .file(new MockMultipartFile("files", "notes.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8)))
                .param("name", name)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Only .csv files are supported: notes.txt"));
        assertThat(countFiles()).isEqualTo(before);
        verify(airflowClient, never()).triggerPipeline(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void emptyFilesAreReportedInsteadOfSilentlyDropped() throws Exception {
        JsonNode response = upload("empty-" + UUID.randomUUID(), false, csv("data.csv", "a\n1\n"), csv("blank.csv", ""));
        assertThat(response.get("fileCount").asInt()).isEqualTo(1);
        assertThat(response.get("skippedFiles").get(0).asString()).isEqualTo("blank.csv (empty file)");
    }

    @Test
    void failedAirflowTriggerMarksTheRunFailedImmediately() throws Exception {
        when(airflowClient.triggerPipeline(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new AirflowClient.TriggerResult(false, "Airflow is not reachable at http://localhost:8080 (Connection refused)"));
        JsonNode response = upload("down-" + UUID.randomUUID(), false, csv("t.csv", "a\n1\n"));
        assertThat(response.get("status").asString()).isEqualTo("FAILED");
        assertThat(response.get("airflowTriggered").asBoolean()).isFalse();

        JsonNode details = versionDetails(response);
        assertThat(details.get("version").get("status").asString()).isEqualTo("FAILED");
        assertThat(details.get("version").get("errorMessage").asString()).contains("Connection refused");

        // Retry once Airflow is back.
        when(airflowClient.triggerPipeline(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new AirflowClient.TriggerResult(true, "Airflow accepted the run (200)"));
        String rerunUrl = "/api/datasets/" + response.get("datasetId").asLong() + "/versions/" + response.get("versionId").asLong() + "/rerun";
        mvc.perform(post(rerunUrl).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.airflowTriggered").value(true));
        // A second retry while it is pending is a conflict.
        mvc.perform(post(rerunUrl).header("Authorization", "Bearer " + token))
            .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ pipeline callbacks

    @Test
    void partialRunExposesErrorAndFailedTables() throws Exception {
        JsonNode upload = upload("partial-" + UUID.randomUUID(), false, csv("a.csv", "x\n1\n"), csv("b.csv", "x\n"));
        String runId = upload.get("pipelineRunId").asString();
        mvc.perform(post("/api/pipeline/" + runId + "/start").header("X-Pipeline-Token", PIPELINE_TOKEN))
            .andExpect(status().isOk());
        postTable(runId, "a", 1, "abc");
        completeRun(runId, "partial", "[{\"table_name\":\"b\",\"error\":\"Header-only CSV: no data rows\"}]");

        JsonNode details = versionDetails(upload);
        assertThat(details.get("version").get("status").asString()).isEqualTo("PARTIAL");
        assertThat(details.get("version").get("errorMessage").asString()).contains("b (Header-only CSV: no data rows)");
        assertThat(details.get("version").get("startedAt").isNull()).isFalse();
        assertThat(details.get("version").get("finishedAt").isNull()).isFalse();
        assertThat(details.get("failedTables").get(0).get("tableName").asString()).isEqualTo("b");
        assertThat(details.get("tables").get(0).get("llmStatus").asString()).isEqualTo("unavailable");
        assertThat(details.get("qualityScore").asDouble()).isEqualTo(100.0);
    }

    @Test
    void tableChangeStatusUsesContentHashAndReportsRemovedTables() throws Exception {
        String name = "changes-" + UUID.randomUUID();
        JsonNode v1 = upload(name, false, csv("keep.csv", "a\n1\n"), csv("edit.csv", "a\n1\n"), csv("gone.csv", "a\n1\n"));
        String run1 = v1.get("pipelineRunId").asString();
        postTable(run1, "keep", 1, "hash-keep");
        postTable(run1, "edit", 1, "hash-edit-1");
        postTable(run1, "gone", 1, "hash-gone");
        completeRun(run1, "success", null);

        JsonNode v2 = upload(name, false, csv("keep.csv", "a\n1\n"), csv("edit.csv", "a\n2\n"));
        String run2 = v2.get("pipelineRunId").asString();
        postTable(run2, "keep", 1, "hash-keep");
        postTable(run2, "edit", 1, "hash-edit-2");
        completeRun(run2, "success", null);

        JsonNode tables = versionDetails(v2).get("tables");
        assertThat(statusOf(tables, "keep")).isEqualTo("UNCHANGED");
        assertThat(statusOf(tables, "edit")).isEqualTo("MODIFIED");
        assertThat(statusOf(tables, "gone")).isEqualTo("REMOVED");
    }

    @Test
    void selectedTableEndpointReturnsOneTable() throws Exception {
        JsonNode upload = upload("table-" + UUID.randomUUID(), false, csv("t.csv", "a\n1\n"));
        postTable(upload.get("pipelineRunId").asString(), "t", 1, "h");
        JsonNode table = versionDetails(upload).get("tables").get(0);
        String base = "/api/datasets/" + upload.get("datasetId").asLong() + "/versions/" + upload.get("versionId").asLong();
        mvc.perform(get(base + "/tables/" + table.get("tableVersionId").asLong()).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tableName").value("t"));
        mvc.perform(get(base + "/tables/999999").header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asString();
    }

    private MockMultipartFile csv(String name, String content) {
        return new MockMultipartFile("files", name, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode upload(String name, boolean rerun, MockMultipartFile... files) throws Exception {
        var request = multipart("/api/datasets/upload");
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        MvcResult result = mvc.perform(request
                .param("name", name)
                .param("rerunExisting", String.valueOf(rerun))
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode versionDetails(JsonNode upload) throws Exception {
        MvcResult result = mvc.perform(get("/api/datasets/" + upload.get("datasetId").asLong() + "/versions/" + upload.get("versionId").asLong())
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void postTable(String runId, String table, int rows, String contentHash) throws Exception {
        String body = """
            {"table_name": "%s", "status": "completed", "llm_status": "unavailable",
             "dataset_metrics": {"row_count": %d, "column_count": 1, "duplicate_rows": 0, "duplicate_percent": 0},
             "column_metrics": [{"name": "a", "data_type": "int64", "missing_count": 0, "missing_percent": 0, "distinct_count": 1}],
             "data_signature": {"row_count": %d, "column_count": 1, "content_hash": "%s", "schema_hash": "s1"}}
            """.formatted(table, rows, rows, contentHash);
        mvc.perform(post("/api/pipeline/" + runId + "/table-analysis")
                .header("X-Pipeline-Token", PIPELINE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
    }

    private void completeRun(String runId, String status, String failedTablesJson) throws Exception {
        String body = "{\"status\":\"" + status + "\",\"failed_tables\":" + (failedTablesJson == null ? "[]" : failedTablesJson) + "}";
        mvc.perform(post("/api/pipeline/" + runId + "/complete")
                .header("X-Pipeline-Token", PIPELINE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
    }

    private String statusOf(JsonNode tables, String name) {
        for (JsonNode table : tables) {
            if (table.get("tableName").asString().equals(name)) {
                return table.get("changeStatus").asString();
            }
        }
        return null;
    }

    private void stripHashesFromSignature(long versionId) {
        var version = versionRepository.findById(versionId).orElseThrow();
        version.setDataSignatureJson(version.getDataSignatureJson().replaceAll(",\\s*\"sha256\"\\s*:\\s*\"[0-9a-f]+\"", ""));
        versionRepository.save(version);
    }

    private long countFiles() throws Exception {
        try (var paths = Files.walk(storageRoot)) {
            return paths.count();
        }
    }
}
