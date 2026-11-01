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

    // ------------------------------------------------------------------ format and date-order rules

    @Test
    void formatAndDateOrderRulesReadTheValuesThepipelineMeasured() throws Exception {
        JsonNode v1 = upload("measured-" + UUID.randomUUID(), csv("accounts.csv", "id\n1\n"));
        long datasetId = v1.get("datasetId").asLong();
        long versionId = v1.get("versionId").asLong();
        String runId = v1.get("pipelineRunId").asString();
        createRule(datasetId, "{\"tableName\":\"accounts\",\"columnName\":\"email\",\"type\":\"MIN_FORMAT_VALID_PERCENT\",\"format\":\"EMAIL\",\"threshold\":99,\"severity\":\"CRITICAL\"}");
        createRule(datasetId, "{\"tableName\":\"accounts\",\"columnName\":\"email\",\"type\":\"MIN_FORMAT_VALID_PERCENT\",\"format\":\"EMAIL\",\"threshold\":90}");
        createRule(datasetId, "{\"tableName\":\"accounts\",\"columnName\":\"opened_on\",\"type\":\"DATE_ORDER\",\"secondColumn\":\"closed_on\"}");
        createRule(datasetId, "{\"tableName\":\"accounts\",\"columnName\":\"opened_on\",\"type\":\"DATE_ORDER\",\"secondColumn\":\"closed_on\",\"threshold\":10}");
        createRule(datasetId, "{\"tableName\":\"accounts\",\"columnName\":\"closed_on\",\"type\":\"DATE_ORDER\",\"secondColumn\":\"opened_on\",\"threshold\":50}");
        createRule(datasetId, "{\"tableName\":\"accounts\",\"columnName\":\"name\",\"type\":\"MIN_FORMAT_VALID_PERCENT\",\"format\":\"URL\",\"threshold\":50}");

        postTableRaw(runId, """
            {"table_name": "accounts", "status": "completed", "llm_status": "unavailable",
             "dataset_metrics": {"row_count": 100, "column_count": 4, "duplicate_rows": 0, "duplicate_percent": 0},
             "column_metrics": [
               {"name": "email", "data_type": "object", "missing_count": 0, "missing_percent": 0, "distinct_count": 100,
                "format_stats": {"EMAIL": {"checked": 100, "valid": 95, "invalid_examples": ["c.mail.com", "d@mail"]}}},
               {"name": "opened_on", "data_type": "object", "missing_count": 0, "missing_percent": 0, "distinct_count": 90},
               {"name": "closed_on", "data_type": "object", "missing_count": 20, "missing_percent": 20, "distinct_count": 70},
               {"name": "name", "data_type": "int64", "missing_count": 0, "missing_percent": 0, "distinct_count": 90}],
             "cross_checks": {"date_order": [{"first": "opened_on", "second": "closed_on", "compared": 80,
                "first_after_second": 4, "second_after_first": 70,
                "first_after_second_examples": ["opened_on=2020-06-01 | closed_on=2020-05-01"], "second_after_first_examples": []}]},
             "data_signature": {"row_count": 100, "column_count": 4, "content_hash": "m1", "schema_hash": "s4"}}
            """);
        completeRun(runId);

        JsonNode results = getJson("/api/datasets/" + datasetId + "/versions/" + versionId + "/rule-results");
        JsonNode strict = result(results, "accounts.email has at least 99% of its values as an email address");
        assertThat(strict.get("status").asString()).isEqualTo("FAIL");
        assertThat(strict.get("actual").asDouble()).isEqualTo(95.0);
        assertThat(strict.get("message").asString()).isEqualTo("95% of the filled values are an email address (minimum 99%), for example 'c.mail.com'");
        assertThat(result(results, "accounts.email has at least 90% of its values as an email address").get("status").asString()).isEqualTo("PASS");

        JsonNode strictDates = result(results, "accounts.opened_on is not after accounts.closed_on");
        assertThat(strictDates.get("status").asString()).isEqualTo("FAIL");
        assertThat(strictDates.get("message").asString())
            .isEqualTo("4 of 80 row(s) have opened_on after closed_on (5%, limit 0%), for example opened_on=2020-06-01 | closed_on=2020-05-01");
        assertThat(result(results, "accounts.opened_on is not after accounts.closed_on (up to 10% of rows may break this)").get("status").asString()).isEqualTo("PASS");
        // The other direction reads the other counter: closed_on is after opened_on on 70 of 80 rows.
        JsonNode reversed = result(results, "accounts.closed_on is not after accounts.opened_on (up to 50% of rows may break this)");
        assertThat(reversed.get("status").asString()).isEqualTo("FAIL");
        assertThat(reversed.get("actual").asDouble()).isEqualTo(87.5);
        // A column that is not text has nothing measured: the rule is skipped, not failed.
        assertThat(results.get("results").toString()).contains("No text values were measured for name");
    }

    @Test
    void formatAndDateRulesNeedTheirExtraInput() throws Exception {
        long datasetId = upload("format-input-" + UUID.randomUUID(), csv("t.csv", "a\n1\n")).get("datasetId").asLong();
        assertRule(datasetId, "{\"tableName\":\"t\",\"columnName\":\"a\",\"type\":\"MIN_FORMAT_VALID_PERCENT\",\"threshold\":90}", 400);
        assertRule(datasetId, "{\"tableName\":\"t\",\"columnName\":\"a\",\"type\":\"MIN_FORMAT_VALID_PERCENT\",\"format\":\"IBAN\",\"threshold\":90}", 400);
        assertRule(datasetId, "{\"tableName\":\"t\",\"columnName\":\"a\",\"type\":\"MIN_FORMAT_VALID_PERCENT\",\"format\":\"email\",\"threshold\":120}", 400);
        assertRule(datasetId, "{\"tableName\":\"t\",\"columnName\":\"a\",\"type\":\"DATE_ORDER\"}", 400);
        assertRule(datasetId, "{\"tableName\":\"t\",\"columnName\":\"a\",\"type\":\"DATE_ORDER\",\"secondColumn\":\"a\"}", 400);
        assertRule(datasetId, "{\"tableName\":\"t\",\"columnName\":\"a\",\"type\":\"DATE_ORDER\",\"secondColumn\":\"b\"}", 201);
        assertRule(datasetId, "{\"tableName\":\"t\",\"columnName\":\"a\",\"type\":\"MIN_FORMAT_VALID_PERCENT\",\"format\":\"email\",\"threshold\":90}", 201);
    }

    // ------------------------------------------------------------------ column roles and quality dimensions

    @Test
    void columnRolesAndDimensionsComeWithTheFiguresTheyAreMadeOf() throws Exception {
        JsonNode v1 = upload("dimensions-" + UUID.randomUUID(), csv("accounts.csv", "id\n1\n"));
        long datasetId = v1.get("datasetId").asLong();
        String runId = v1.get("pipelineRunId").asString();
        postTableRaw(runId, """
            {"table_name": "accounts", "status": "completed", "llm_status": "unavailable",
             "dataset_metrics": {"row_count": 100, "column_count": 3, "duplicate_rows": 5, "duplicate_percent": 5},
             "column_metrics": [
               {"name": "email", "data_type": "object", "missing_count": 0, "missing_percent": 0, "distinct_count": 100,
                "role": "EMAIL", "role_confidence": 1.0, "role_source": "rules",
                "role_evidence": ["the name contains 'email'", "95% of its values look like email values"],
                "format_stats": {"EMAIL": {"checked": 100, "valid": 95, "invalid_examples": []}}},
               {"name": "opened_on", "data_type": "object", "missing_count": 0, "missing_percent": 0, "distinct_count": 90,
                "role": "DATE", "role_confidence": 0.9, "role_source": "llm", "role_evidence": ["the model chose it: opening date"],
                "format_stats": {"DATE": {"checked": 100, "valid": 100, "invalid_examples": []}}},
               {"name": "closed_on", "data_type": "object", "missing_count": 20, "missing_percent": 20, "distinct_count": 70,
                "role": "DATE", "role_confidence": 0.9, "role_source": "rules", "role_evidence": ["its values look like dates"],
                "format_stats": {"DATE": {"checked": 80, "valid": 80, "invalid_examples": []}}}],
             "cross_checks": {"date_order": [{"first": "opened_on", "second": "closed_on", "compared": 80,
                "first_after_second": 4, "second_after_first": 70, "first_after_second_examples": [], "second_after_first_examples": []}]},
             "data_signature": {"row_count": 100, "column_count": 3, "content_hash": "dim", "schema_hash": "s3"}}
            """);
        completeRun(runId);
        String url = "/api/datasets/" + datasetId + "/versions/" + v1.get("versionId").asLong();

        JsonNode details = getJson(url);
        JsonNode email = details.get("tables").get(0).get("columns").get(0);
        assertThat(email.get("role").asString()).isEqualTo("EMAIL");
        assertThat(email.get("roleConfidence").asDouble()).isEqualTo(1.0);
        assertThat(email.get("roleSource").asString()).isEqualTo("rules");
        assertThat(email.get("roleEvidence").size()).isEqualTo(2);
        assertThat(details.get("tables").get(0).get("columns").get(1).get("roleSource").asString()).isEqualTo("llm");

        // 20 of 300 cells are empty; 5 of 100 rows repeat; 275 of 280 filled email/date values follow their format.
        assertThat(dimension(details, "COMPLETENESS").get("numerator").asLong()).isEqualTo(280);
        assertThat(dimension(details, "COMPLETENESS").get("denominator").asLong()).isEqualTo(300);
        assertThat(dimension(details, "UNIQUENESS").get("percent").asDouble()).isEqualTo(95.0);
        JsonNode validity = dimension(details, "VALIDITY");
        assertThat(validity.get("numerator").asLong()).isEqualTo(275);
        assertThat(validity.get("denominator").asLong()).isEqualTo(280);
        assertThat(validity.get("basis").asString()).contains("accounts.email (email)", "accounts.closed_on (date)");

        // Consistency is only measured once a date-order rule is approved.
        JsonNode before = dimension(details, "CONSISTENCY");
        assertThat(before.get("percent").isNull()).isTrue();
        assertThat(before.get("basis").asString()).contains("no date-order rule is approved");
        createRule(datasetId, "{\"tableName\":\"accounts\",\"columnName\":\"opened_on\",\"type\":\"DATE_ORDER\",\"secondColumn\":\"closed_on\"}");
        JsonNode consistency = dimension(getJson(url), "CONSISTENCY");
        assertThat(consistency.get("numerator").asLong()).isEqualTo(76);
        assertThat(consistency.get("denominator").asLong()).isEqualTo(80);
        assertThat(consistency.get("percent").asDouble()).isEqualTo(95.0);
    }

    @Test
    void dimensionsAreNotMadeUpWhenNothingCanBeMeasured() throws Exception {
        JsonNode v1 = upload("no-formats-" + UUID.randomUUID(), csv("t.csv", "a\n1\n"));
        postTable(v1.get("pipelineRunId").asString(), "t", 10, "nf", 0, col("a", "int64", 0, 0, 10));
        completeRun(v1.get("pipelineRunId").asString());

        JsonNode details = getJson("/api/datasets/" + v1.get("datasetId").asLong() + "/versions/" + v1.get("versionId").asLong());

        assertThat(dimension(details, "VALIDITY").get("percent").isNull()).isTrue();
        assertThat(dimension(details, "VALIDITY").get("basis").asString()).startsWith("Not measured");
        assertThat(dimension(details, "COMPLETENESS").get("percent").asDouble()).isEqualTo(100.0);
        assertThat(details.get("tables").get(0).get("columns").get(0).get("role").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ rules suggested from the goal

    @Test
    void suggestedRulesWaitForApprovalAndAreNeverSuggestedTwice() throws Exception {
        JsonNode v1 = upload("suggest-" + UUID.randomUUID(), csv("accounts.csv", "id\n1\n"));
        long datasetId = v1.get("datasetId").asLong();
        long versionId = v1.get("versionId").asLong();
        String runId = v1.get("pipelineRunId").asString();
        String body = """
            {"rules": [
              {"table_name": "accounts", "column_name": "email", "type": "MIN_FORMAT_VALID_PERCENT", "format": "EMAIL",
               "threshold": 99, "severity": "CRITICAL", "rationale": "email is needed to contact customers"},
              {"table_name": "accounts", "column_name": "email", "type": "NOT_NULL", "severity": "WARNING", "rationale": "must be filled"},
              {"table_name": "accounts", "column_name": "email", "type": "SOMETHING_ELSE", "severity": "WARNING", "rationale": "invalid"}]}
            """;

        mvc.perform(post("/api/pipeline/" + runId + "/rule-suggestions").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/pipeline/" + runId + "/rule-suggestions").header("X-Pipeline-Token", PIPELINE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());

        verify(eventStreamService).publish(argThat((LiveEvent event) -> event.type().equals("RULES_SUGGESTED")
            && event.message().equals("2 quality rule(s) suggested from your goal")));
        JsonNode rules = getJson("/api/datasets/" + datasetId + "/rules");
        assertThat(rules.size()).isEqualTo(2);
        JsonNode first = rules.get(0);
        assertThat(first.get("state").asString()).isEqualTo("SUGGESTED");
        assertThat(first.get("origin").asString()).isEqualTo("AI");
        assertThat(first.get("rationale").asString()).isEqualTo("email is needed to contact customers");
        assertThat(first.get("description").asString()).isEqualTo("accounts.email has at least 99% of its values as an email address");

        // A suggestion is not checked until it is approved.
        postTableRaw(runId, """
            {"table_name": "accounts", "status": "completed", "llm_status": "unavailable",
             "dataset_metrics": {"row_count": 10, "column_count": 1, "duplicate_rows": 0, "duplicate_percent": 0},
             "column_metrics": [{"name": "email", "data_type": "object", "missing_count": 0, "missing_percent": 0, "distinct_count": 10,
                "format_stats": {"EMAIL": {"checked": 10, "valid": 10, "invalid_examples": []}}}],
             "data_signature": {"row_count": 10, "column_count": 1, "content_hash": "sg", "schema_hash": "s1"}}
            """);
        completeRun(runId);
        assertThat(getJson("/api/datasets/" + datasetId + "/versions/" + versionId + "/rule-results").get("summary").get("total").asInt()).isZero();

        long formatRuleId = first.get("id").asLong();
        JsonNode approved = json(mvc.perform(post("/api/datasets/" + datasetId + "/rules/" + formatRuleId + "/approve")
            .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn());
        assertThat(approved.get("state").asString()).isEqualTo("ACTIVE");
        assertThat(approved.get("origin").asString()).isEqualTo("AI");
        JsonNode evaluated = postJson("/api/datasets/" + datasetId + "/versions/" + versionId + "/rules/evaluate");
        assertThat(evaluated.get("summary").get("total").asInt()).isEqualTo(1);
        assertThat(evaluated.get("summary").get("passed").asInt()).isEqualTo(1);

        // Turned down: hidden, and the same suggestion does not come back on the next run.
        long dismissedId = rules.get(1).get("id").asLong();
        mvc.perform(post("/api/datasets/" + datasetId + "/rules/" + dismissedId + "/dismiss")
            .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        assertThat(getJson("/api/datasets/" + datasetId + "/rules").size()).isEqualTo(1);
        clearInvocations(eventStreamService);
        mvc.perform(post("/api/pipeline/" + runId + "/rule-suggestions").header("X-Pipeline-Token", PIPELINE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        assertThat(getJson("/api/datasets/" + datasetId + "/rules").size()).isEqualTo(1);
        verify(eventStreamService, never()).publish(argThat((LiveEvent event) -> event.type().equals("RULES_SUGGESTED")));
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

    private void postTableRaw(String runId, String body) throws Exception {
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

    private JsonNode dimension(JsonNode details, String key) {
        for (JsonNode dimension : details.get("dimensions")) {
            if (dimension.get("key").asString().equals(key)) {
                return dimension;
            }
        }
        throw new AssertionError("No dimension " + key);
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
