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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import wevioo.backend.dto.LiveEvent;
import wevioo.backend.service.AirflowClient;
import wevioo.backend.service.EventStreamService;
import wevioo.backend.service.RuleService;
import wevioo.backend.service.RunRecovery;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:quality-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.admin-username=admin",
    "app.admin-password=test-password",
    "app.pipeline-api-token=test-pipeline-token",
    "app.watchdog-interval-ms=3600000"
})
@AutoConfigureMockMvc
class QualityFeaturesTest {
    private static final String PIPELINE_TOKEN = "test-pipeline-token";

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.storage-root", () -> storageRoot.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RuleService ruleService;
    @Autowired RunRecovery runRecovery;
    @MockitoBean AirflowClient airflowClient;
    @MockitoSpyBean EventStreamService eventStreamService;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        when(airflowClient.triggerPipeline(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new AirflowClient.TriggerResult(true, "Airflow accepted the run (200)"));
        // Runs left over by other tests are still running as far as Airflow is concerned.
        when(airflowClient.getDagRunState(anyString())).thenReturn("running");
        token = login();
    }

    // ------------------------------------------------------------------ version diff

    @Test
    void diffShowsRowScoreAndSchemaChangesBetweenVersions() throws Exception {
        String name = "diff-" + UUID.randomUUID();
        JsonNode v1 = upload(name, csv("orders.csv", "id,note\n1,a\n"), csv("customers.csv", "id\n1\n"));
        String run1 = v1.get("pipelineRunId").asString();
        postTable(run1, "orders", 10, "h1", 0, col("id", "int64", 0, 0, 10), col("note", "object", 0, 0, 9));
        postTable(run1, "customers", 5, "h2", 0, col("id", "int64", 0, 0, 5));
        completeRun(run1);

        JsonNode v2 = upload(name, csv("orders.csv", "id,note,tag\n1,a,b\n"));
        String run2 = v2.get("pipelineRunId").asString();
        postTable(run2, "orders", 8, "h3", 0, col("id", "int64", 0, 0, 8), col("note", "object", 2, 20, 6), col("tag", "object", 0, 0, 2));
        completeRun(run2);

        JsonNode diff = getJson("/api/datasets/" + v2.get("datasetId").asLong() + "/versions/" + v2.get("versionId").asLong() + "/diff");
        assertThat(diff.get("from").get("versionNumber").asInt()).isEqualTo(1);
        assertThat(diff.get("to").get("versionNumber").asInt()).isEqualTo(2);
        assertThat(diff.get("tablesRemoved").asInt()).isEqualTo(1);
        assertThat(diff.get("tablesChanged").asInt()).isEqualTo(1);
        assertThat(diff.get("rowsFrom").asLong()).isEqualTo(15);
        assertThat(diff.get("rowsTo").asLong()).isEqualTo(8);
        assertThat(diff.get("scoreFrom").asDouble()).isEqualTo(100.0);
        assertThat(diff.get("scoreDelta").asDouble()).isLessThan(0);

        JsonNode orders = table(diff, "orders");
        assertThat(orders.get("change").asString()).isEqualTo("CHANGED");
        assertThat(orders.get("rowsFrom").asLong()).isEqualTo(10);
        assertThat(orders.get("rowsTo").asLong()).isEqualTo(8);
        assertThat(orders.get("columnsAdded").get(0).asString()).isEqualTo("tag");
        assertThat(orders.get("missingShifts").get(0).get("column").asString()).isEqualTo("note");
        assertThat(orders.get("missingShifts").get(0).get("delta").asDouble()).isEqualTo(20.0);
        assertThat(table(diff, "customers").get("change").asString()).isEqualTo("REMOVED");

        // The older version can also be named explicitly, and a first version has nothing to compare with.
        JsonNode explicit = getJson("/api/datasets/" + v2.get("datasetId").asLong() + "/versions/" + v2.get("versionId").asLong()
            + "/diff?against=" + v1.get("versionId").asLong());
        assertThat(explicit.get("from").get("id").asLong()).isEqualTo(v1.get("versionId").asLong());
        mvc.perform(get("/api/datasets/" + v1.get("datasetId").asLong() + "/versions/" + v1.get("versionId").asLong() + "/diff")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());
    }

    @Test
    void identicalTablesAreReportedAsUnchanged() throws Exception {
        String name = "same-" + UUID.randomUUID();
        JsonNode v1 = upload(name, csv("t.csv", "a\n1\n"));
        postTable(v1.get("pipelineRunId").asString(), "t", 3, "same-hash", 0, col("a", "int64", 0, 0, 3));
        completeRun(v1.get("pipelineRunId").asString());
        JsonNode v2 = upload(name, csv("t.csv", "a\n2\n"));
        postTable(v2.get("pipelineRunId").asString(), "t", 3, "same-hash", 0, col("a", "int64", 0, 0, 3));
        completeRun(v2.get("pipelineRunId").asString());

        JsonNode diff = getJson("/api/datasets/" + v2.get("datasetId").asLong() + "/versions/" + v2.get("versionId").asLong() + "/diff");
        assertThat(diff.get("tablesUnchanged").asInt()).isEqualTo(1);
        assertThat(table(diff, "t").get("change").asString()).isEqualTo("UNCHANGED");
    }

    // ------------------------------------------------------------------ rules

    @Test
    void ruleInputIsValidated() throws Exception {
        long datasetId = upload("rules-input-" + UUID.randomUUID(), csv("t.csv", "a\n1\n")).get("datasetId").asLong();
        assertRule(datasetId, "{\"tableName\":\"t\",\"type\":\"NOPE\"}", 400);
        assertRule(datasetId, "{\"tableName\":\"t\",\"type\":\"NOT_NULL\"}", 400);
        assertRule(datasetId, "{\"type\":\"MIN_ROWS\",\"threshold\":5}", 400);
        assertRule(datasetId, "{\"tableName\":\"t\",\"type\":\"MIN_ROWS\"}", 400);
        assertRule(datasetId, "{\"tableName\":\"t\",\"columnName\":\"a\",\"type\":\"MAX_MISSING_PERCENT\",\"threshold\":150}", 400);
        assertRule(datasetId, "{\"tableName\":\"t\",\"columnName\":\"a\",\"type\":\"NOT_NULL\",\"severity\":\"LOUD\"}", 400);
        assertRule(datasetId, "{\"tableName\":\"t\",\"columnName\":\"a\",\"type\":\"not_null\",\"severity\":\"critical\"}", 201);
        mvc.perform(get("/api/datasets/999999/rules").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }

    @Test
    void rulesAreEvaluatedWhenARunCompletesAndFailuresRaiseAnAlert() throws Exception {
        JsonNode v1 = upload("rules-run-" + UUID.randomUUID(), csv("orders.csv", "id,note\n1,a\n"));
        long datasetId = v1.get("datasetId").asLong();
        long versionId = v1.get("versionId").asLong();
        String runId = v1.get("pipelineRunId").asString();
        long notNullId = createRule(datasetId, "{\"tableName\":\"orders\",\"columnName\":\"id\",\"type\":\"NOT_NULL\",\"severity\":\"CRITICAL\"}");
        createRule(datasetId, "{\"tableName\":\"orders\",\"columnName\":\"note\",\"type\":\"MAX_MISSING_PERCENT\",\"threshold\":10}");
        createRule(datasetId, "{\"tableName\":\"orders\",\"columnName\":\"id\",\"type\":\"UNIQUE\"}");
        createRule(datasetId, "{\"tableName\":\"orders\",\"type\":\"MIN_ROWS\",\"threshold\":100,\"severity\":\"CRITICAL\"}");
        createRule(datasetId, "{\"tableName\":\"orders\",\"type\":\"MAX_ROW_DROP_PERCENT\",\"threshold\":10}");
        createRule(datasetId, "{\"tableName\":\"orders\",\"type\":\"MIN_QUALITY_SCORE\",\"threshold\":90}");
        createRule(datasetId, "{\"tableName\":\"orders\",\"type\":\"MAX_DUPLICATE_PERCENT\",\"threshold\":5}");
        createRule(datasetId, "{\"tableName\":\"missing_table\",\"type\":\"MIN_ROWS\",\"threshold\":1}");
        createRule(datasetId, "{\"tableName\":\"orders\",\"columnName\":\"ghost\",\"type\":\"NOT_NULL\",\"enabled\":false}");

        postTable(runId, "orders", 50, "h", 0, col("id", "int64", 0, 0, 50), col("note", "object", 10, 20, 8));
        completeRun(runId);

        JsonNode results = getJson("/api/datasets/" + datasetId + "/versions/" + versionId + "/rule-results");
        assertThat(results.get("summary").get("total").asInt()).isEqualTo(8); // the disabled rule is not evaluated
        assertThat(results.get("summary").get("failed").asInt()).isEqualTo(2); // 20% missing, only 50 rows
        assertThat(results.get("summary").get("criticalFailed").asInt()).isEqualTo(1);
        assertThat(results.get("summary").get("skipped").asInt()).isEqualTo(2); // no earlier version, table missing
        assertThat(result(results, "orders.id has no missing values").get("status").asString()).isEqualTo("PASS");
        assertThat(result(results, "orders.id is unique").get("status").asString()).isEqualTo("PASS");
        JsonNode missing = result(results, "orders.note has at most 10% missing values");
        assertThat(missing.get("status").asString()).isEqualTo("FAIL");
        assertThat(missing.get("actual").asDouble()).isEqualTo(20.0);
        assertThat(missing.get("message").asString()).contains("20% missing (limit 10%)");
        assertThat(result(results, "orders has at least 100 rows").get("message").asString()).isEqualTo("50 row(s) (minimum 100)");
        assertThat(result(results, "orders loses at most 10% of its rows between versions").get("status").asString()).isEqualTo("SKIPPED");
        assertThat(result(results, "missing_table has at least 1 rows").get("status").asString()).isEqualTo("SKIPPED");

        verify(eventStreamService).publish(argThat((LiveEvent event) -> event.type().equals("RULES_FAILED")
            && event.versionId().equals(versionId) && event.message().equals("2 of 8 quality rule(s) failed (1 critical)")));

        // The summary is part of the version details and the dataset list.
        JsonNode details = getJson("/api/datasets/" + datasetId + "/versions/" + versionId);
        assertThat(details.get("ruleSummary").get("failed").asInt()).isEqualTo(2);
        JsonNode datasets = getJson("/api/datasets");
        assertThat(findDataset(datasets, datasetId).get("failedRules").asInt()).isEqualTo(2);

        // Deleting a rule removes its results; re-evaluating applies an edited rule.
        mvc.perform(delete("/api/datasets/" + datasetId + "/rules/" + notNullId).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        assertThat(getJson("/api/datasets/" + datasetId + "/versions/" + versionId + "/rule-results").get("summary").get("total").asInt()).isEqualTo(7);
        JsonNode reevaluated = postJson("/api/datasets/" + datasetId + "/versions/" + versionId + "/rules/evaluate");
        assertThat(reevaluated.get("summary").get("total").asInt()).isEqualTo(7);
    }

    @Test
    void rowDropRuleComparesWithThePreviousVersion() throws Exception {
        String name = "drop-" + UUID.randomUUID();
        JsonNode v1 = upload(name, csv("t.csv", "a\n1\n"));
        long datasetId = v1.get("datasetId").asLong();
        postTable(v1.get("pipelineRunId").asString(), "t", 100, "x1", 0, col("a", "int64", 0, 0, 100));
        completeRun(v1.get("pipelineRunId").asString());
        createRule(datasetId, "{\"tableName\":\"t\",\"type\":\"MAX_ROW_DROP_PERCENT\",\"threshold\":10}");

        JsonNode v2 = upload(name, csv("t.csv", "a\n2\n"));
        postTable(v2.get("pipelineRunId").asString(), "t", 60, "x2", 0, col("a", "int64", 0, 0, 60));
        completeRun(v2.get("pipelineRunId").asString());

        JsonNode result = getJson("/api/datasets/" + datasetId + "/versions/" + v2.get("versionId").asLong() + "/rule-results").get("results").get(0);
        assertThat(result.get("status").asString()).isEqualTo("FAIL");
        assertThat(result.get("actual").asDouble()).isEqualTo(40.0);
        assertThat(result.get("message").asString()).isEqualTo("100 to 60 rows (-40%, limit -10%)");
    }

    // ------------------------------------------------------------------ recovery after a restart

    @Test
    void runsUnknownToAirflowAreStartedAgainAfterARestart() throws Exception {
        JsonNode upload = upload("recover-" + UUID.randomUUID(), csv("t.csv", "a\n1\n"));
        String runId = upload.get("pipelineRunId").asString();
        clearInvocations(airflowClient);
        when(airflowClient.triggerPipeline(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new AirflowClient.TriggerResult(true, "Airflow accepted the run (200)"));
        // The backend died before it called Airflow: Airflow has never heard of this run.
        when(airflowClient.getDagRunState(runId)).thenReturn(AirflowClient.NOT_FOUND);

        assertThat(runRecovery.recover()).isGreaterThanOrEqualTo(1);

        verify(airflowClient).triggerPipeline(eq(runId), anyString(), anyString(), argThat(path -> path.endsWith("/v1") || path.endsWith("\\v1")));
        assertThat(versionStatus(upload)).isEqualTo("PENDING");
        verify(eventStreamService).publish(argThat((LiveEvent event) -> event.type().equals("PIPELINE_STARTED") && runId.equals(event.pipelineRunId())));
    }

    @Test
    void runsThatAirflowReportsAsFailedAreFailedAfterARestart() throws Exception {
        JsonNode upload = upload("recover-failed-" + UUID.randomUUID(), csv("t.csv", "a\n1\n"));
        String runId = upload.get("pipelineRunId").asString();
        when(airflowClient.getDagRunState(runId)).thenReturn("failed");

        runRecovery.recover();

        assertThat(versionStatus(upload)).isEqualTo("FAILED");
        assertThat(getJson(versionUrl(upload)).get("version").get("errorMessage").asString()).contains("Airflow reports this run as failed");
    }

    @Test
    void runsStillWorkedOnByAirflowAreLeftAlone() throws Exception {
        JsonNode upload = upload("recover-running-" + UUID.randomUUID(), csv("t.csv", "a\n1\n"));
        String runId = upload.get("pipelineRunId").asString();
        clearInvocations(airflowClient);
        when(airflowClient.getDagRunState(runId)).thenReturn("running");

        runRecovery.recover();

        verify(airflowClient, never()).triggerPipeline(eq(runId), anyString(), anyString(), anyString());
        assertThat(versionStatus(upload)).isEqualTo("PENDING");
    }

    @Test
    void runsWhoseFilesAreGoneCannotBeStartedAgain() throws Exception {
        JsonNode upload = upload("recover-gone-" + UUID.randomUUID(), csv("t.csv", "a\n1\n"));
        String runId = upload.get("pipelineRunId").asString();
        when(airflowClient.getDagRunState(runId)).thenReturn(AirflowClient.NOT_FOUND);
        java.nio.file.Files.walk(Path.of(upload.get("filePath").asString()))
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(path -> path.toFile().delete());

        runRecovery.recover();

        assertThat(versionStatus(upload)).isEqualTo("FAILED");
        assertThat(getJson(versionUrl(upload)).get("version").get("errorMessage").asString()).contains("uploaded files are gone");
    }

    // ------------------------------------------------------------------ helpers

    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"test-password\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return json(result).get("token").asString();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(String url) throws Exception {
        return json(mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode postJson(String url) throws Exception {
        return json(mvc.perform(post(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn());
    }

    private MockMultipartFile csv(String name, String content) {
        return new MockMultipartFile("files", name, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode upload(String name, MockMultipartFile... files) throws Exception {
        var request = multipart("/api/datasets/upload");
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        return json(mvc.perform(request.param("name", name).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn());
    }

    private String versionUrl(JsonNode upload) {
        return "/api/datasets/" + upload.get("datasetId").asLong() + "/versions/" + upload.get("versionId").asLong();
    }

    private String versionStatus(JsonNode upload) throws Exception {
        return getJson(versionUrl(upload)).get("version").get("status").asString();
    }

    private static String col(String name, String type, int missingCount, double missingPercent, int distinct) {
        return "{\"name\":\"%s\",\"data_type\":\"%s\",\"missing_count\":%d,\"missing_percent\":%s,\"distinct_count\":%d}"
            .formatted(name, type, missingCount, missingPercent, distinct);
    }

    private void postTable(String runId, String table, long rows, String hash, double duplicatePercent, String... columns) throws Exception {
        String body = """
            {"table_name": "%s", "status": "completed", "llm_status": "unavailable",
             "dataset_metrics": {"row_count": %d, "column_count": %d, "duplicate_rows": %d, "duplicate_percent": %s},
             "column_metrics": [%s],
             "data_signature": {"row_count": %d, "column_count": %d, "content_hash": "%s", "schema_hash": "s%d"}}
            """.formatted(table, rows, columns.length, Math.round(rows * duplicatePercent / 100), duplicatePercent,
            String.join(",", columns), rows, columns.length, hash, columns.length);
        mvc.perform(post("/api/pipeline/" + runId + "/table-analysis")
                .header("X-Pipeline-Token", PIPELINE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
    }

    private void completeRun(String runId) throws Exception {
        mvc.perform(post("/api/pipeline/" + runId + "/complete")
                .header("X-Pipeline-Token", PIPELINE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"success\",\"failed_tables\":[]}"))
            .andExpect(status().isOk());
    }

    private void assertRule(long datasetId, String body, int expectedStatus) throws Exception {
        mvc.perform(post("/api/datasets/" + datasetId + "/rules")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().is(expectedStatus));
    }

    private long createRule(long datasetId, String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/datasets/" + datasetId + "/rules")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        return json(result).get("id").asLong();
    }

    private JsonNode table(JsonNode diff, String name) {
        for (JsonNode table : diff.get("tables")) {
            if (table.get("tableName").asString().equals(name)) {
                return table;
            }
        }
        throw new AssertionError("No table " + name + " in the diff");
    }

    private JsonNode result(JsonNode results, String description) {
        for (JsonNode result : results.get("results")) {
            if (result.get("description").asString().equals(description)) {
                return result;
            }
        }
        throw new AssertionError("No result for rule: " + description);
    }

    private JsonNode findDataset(JsonNode datasets, long id) {
        for (JsonNode dataset : datasets) {
            if (dataset.get("id").asLong() == id) {
                return dataset;
            }
        }
        throw new AssertionError("Dataset " + id + " not listed");
    }
}
