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
import wevioo.backend.correction.OllamaClient;
import wevioo.backend.service.AirflowClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:corrections-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.admin-username=admin",
    "app.admin-password=test-password",
    "app.pipeline-api-token=test-pipeline-token",
    "app.watchdog-interval-ms=3600000"
})
@AutoConfigureMockMvc
class CorrectionsApiTest {
    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.storage-root", () -> storageRoot.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean AirflowClient airflowClient;
    @MockitoBean OllamaClient ollama;

    private String token;

    private static final String DIRTY = String.join("\n",
        "id,name,country,joined",
        "1, Ann ,france,25/02/2024",
        "2,Bo,France,01/02/2024",
        "3,Cy,FRANCE,2024-03-04",
        "4,Di,N/A,12 Mar 2023",
        "1, Ann ,france,25/02/2024",
        "") + "\n";

    @BeforeEach
    void setUp() throws Exception {
        when(airflowClient.triggerPipeline(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new AirflowClient.TriggerResult(true, "Airflow accepted the run (200)"));
        when(airflowClient.getDagRunState(anyString())).thenReturn("running");
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"test-password\"}"))
            .andExpect(status().isOk()).andReturn();
        token = json(login).get("token").asString();
    }

    // ------------------------------------------------------------------ what could be corrected

    @Test
    void suggestionsAreFoundByReadingTheTable() throws Exception {
        JsonNode upload = upload("suggest-" + UUID.randomUUID(), DIRTY);

        JsonNode found = getJson(url(upload) + "/suggestions?table=people");

        assertThat(found.get("rows").asLong()).isEqualTo(5);
        assertThat(found.get("columns").toString()).isEqualTo("[\"id\",\"name\",\"country\",\"joined\"]");
        assertThat(keys(found)).contains("DROP_DUPLICATE_ROWS", "TRIM:name", "EMPTY_TO_NULL:country", "NORMALIZE_DATE:joined", "REPLACE_VALUES:country");
        JsonNode dates = suggestion(found, "NORMALIZE_DATE:joined");
        assertThat(dates.get("rationale").asString()).contains("can be read two ways");
        JsonNode spelling = suggestion(found, "REPLACE_VALUES:country");
        assertThat(spelling.get("operation").get("params").get("mappings").toString()).contains("\"from\":\"France\"", "\"to\":\"france\"");
        assertThat(suggestion(found, "TRIM:name").get("affectedRows").asLong()).isEqualTo(2);
    }

    @Test
    void anUnknownTableIsNotFoundAndANameIsNeverUsedAsAPath() throws Exception {
        JsonNode upload = upload("path-" + UUID.randomUUID(), DIRTY);

        mvc.perform(get(url(upload) + "/suggestions?table=nope").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(get(url(upload) + "/suggestions?table=../people").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(get(url(upload) + "/suggestions?table=dataset_config").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ preview

    @Test
    void thePreviewCountsWhatEachCorrectionWouldChangeAndWarnsAboutAmbiguousDates() throws Exception {
        JsonNode upload = upload("preview-" + UUID.randomUUID(), DIRTY);
        String body = """
            {"table": "people", "operations": [
              {"type": "TRIM", "column": "name"},
              {"type": "EMPTY_TO_NULL", "column": "country", "params": {"tokens": ["N/A"]}},
              {"type": "NORMALIZE_DATE", "column": "joined"},
              {"type": "DROP_DUPLICATE_ROWS"}]}
            """;

        JsonNode preview = postJson(url(upload) + "/preview", body, 200);

        assertThat(preview.get("rowsBefore").asLong()).isEqualTo(5);
        assertThat(preview.get("rowsAfter").asLong()).isEqualTo(4);
        JsonNode trim = preview.get("operations").get(0);
        assertThat(trim.get("affected").asLong()).isEqualTo(2);
        assertThat(trim.get("examples").get(0).get("before").asString()).isEqualTo(" Ann ");
        assertThat(trim.get("examples").get(0).get("after").asString()).isEqualTo("Ann");
        assertThat(preview.get("operations").get(2).get("review").asLong()).isEqualTo(1);
        assertThat(preview.get("warnings").toString()).contains("can be read as day/month or month/day", "1 row(s) will be removed");
        // The preview only measures: the stored file is untouched.
        assertThat(Files.readString(Path.of(upload.get("filePath").asString()).resolve("people.csv"))).isEqualTo(DIRTY);
    }

    @Test
    void invalidCorrectionsAreRefusedWithAReason() throws Exception {
        JsonNode upload = upload("invalid-" + UUID.randomUUID(), DIRTY);

        assertThat(postJson(url(upload) + "/preview", "{\"table\":\"people\",\"operations\":[{\"type\":\"TRIM\",\"column\":\"ghost\"}]}", 400)
            .get("error").asString()).contains("no column named ghost");
        assertThat(postJson(url(upload) + "/preview", "{\"table\":\"people\",\"operations\":[{\"type\":\"RUN_SQL\",\"column\":\"id\"}]}", 400)
            .get("error").asString()).contains("Unknown correction");
        assertThat(postJson(url(upload) + "/apply", "{\"table\":\"people\",\"operations\":[]}", 400)
            .get("error").asString()).contains("at least one correction");
    }

    // ------------------------------------------------------------------ plain language

    @Test
    void aRequestInPlainLanguageBecomesCheckedOperations() throws Exception {
        JsonNode upload = upload("plain-" + UUID.randomUUID(), DIRTY);
        when(ollama.generateJson(anyString(), anyMap())).thenReturn("""
            {"operations": [
               {"type": "REPLACE_VALUES", "column": "COUNTRY", "params": {"mappings": [{"from": "FRANCE", "to": "France"}, {"from": "france", "to": "France"}]}},
               {"type": "NORMALIZE_DATE", "column": "joined", "params": {"order": "dmy"}},
               {"type": "TRIM", "column": "salary"},
               {"type": "DROP_TABLE", "column": "id"}],
             "assumptions": "Dates are written day first."}
            """);

        JsonNode plan = postJson(url(upload) + "/interpret", "{\"table\":\"people\",\"instruction\":\"write France the same way and read dates day first\"}", 200);

        assertThat(plan.get("operations").size()).isEqualTo(2);
        assertThat(plan.get("operations").get(0).get("operation").get("column").asString()).isEqualTo("country");   // real spelling
        assertThat(plan.get("operations").get(1).get("operation").get("params").get("order").asString()).isEqualTo("DMY");
        assertThat(plan.get("operations").get(0).get("description").asString()).isEqualTo("Replace 2 value(s) in country");
        assertThat(plan.get("assumptions").asString()).isEqualTo("Dates are written day first.");
        assertThat(plan.get("rejected").toString()).contains("no column named salary", "Unknown correction: DROP_TABLE");
        // The model saw the real columns and values, not invented ones.
        verify(ollama).generateJson(org.mockito.ArgumentMatchers.argThat(prompt -> prompt.contains("France (1)") && prompt.contains("TABLE: people (5 rows)")), anyMap());
    }

    @Test
    void whenTheModelIsNotAvailableTheAnswerIsAClearError() throws Exception {
        JsonNode upload = upload("nomodel-" + UUID.randomUUID(), DIRTY);
        when(ollama.generateJson(anyString(), anyMap())).thenThrow(new OllamaClient.LlmUnavailableException("The model is not reachable"));

        JsonNode error = postJson(url(upload) + "/interpret", "{\"table\":\"people\",\"instruction\":\"fix it\"}", 503);

        assertThat(error.get("error").asString()).isEqualTo("The model is not reachable");
    }

    // ------------------------------------------------------------------ applying

    @Test
    void applyingCreatesANewVersionAndLeavesTheOriginalUntouched() throws Exception {
        JsonNode upload = upload("apply-" + UUID.randomUUID(), DIRTY);
        Path originalFolder = Path.of(upload.get("filePath").asString());
        String body = """
            {"table": "people", "operations": [
              {"type": "TRIM", "column": "name"},
              {"type": "REPLACE_VALUES", "column": "country", "params": {"ignoreCase": true, "mappings": [{"from": "france", "to": "France"}]}},
              {"type": "EMPTY_TO_NULL", "column": "country", "params": {"tokens": ["N/A"]}},
              {"type": "NORMALIZE_DATE", "column": "joined", "params": {"order": "DMY"}},
              {"type": "DROP_DUPLICATE_ROWS"}]}
            """;

        JsonNode applied = postJson(url(upload) + "/apply", body, 200);

        assertThat(applied.get("versionNumber").asInt()).isEqualTo(2);
        assertThat(applied.get("airflowTriggered").asBoolean()).isTrue();
        verify(airflowClient, atLeast(2)).triggerPipeline(anyString(), anyString(), anyString(), anyString());
        Path newFolder = Path.of(applied.get("filePath").asString());
        assertThat(Files.readString(newFolder.resolve("people.csv"))).isEqualTo(String.join("\n",
            "id,name,country,joined",
            "1,Ann,France,2024-02-25",
            "2,Bo,France,2024-02-01",
            "3,Cy,France,2024-03-04",
            "4,Di,,2023-03-12",
            ""));
        assertThat(Files.readString(originalFolder.resolve("people.csv"))).isEqualTo(DIRTY);

        // The new version knows where it comes from and what was done.
        JsonNode details = getJson("/api/datasets/" + applied.get("datasetId").asLong() + "/versions/" + applied.get("versionId").asLong());
        assertThat(details.get("version").get("derivedFromVersionNumber").asInt()).isEqualTo(1);
        JsonNode log = getJson("/api/datasets/" + applied.get("datasetId").asLong() + "/versions/" + applied.get("versionId").asLong() + "/corrections");
        assertThat(log.get("derivedFromVersionNumber").asInt()).isEqualTo(1);
        assertThat(log.get("rowsBefore").asLong()).isEqualTo(5);
        assertThat(log.get("rowsAfter").asLong()).isEqualTo(4);
        assertThat(log.get("operations").size()).isEqualTo(5);
        assertThat(log.get("operations").get(0).get("description").asString()).isEqualTo("Remove spaces around the values of name");
        // The original version has no correction log.
        assertThat(getJson(url(upload)).size()).isZero();
    }

    @Test
    void theCorrectedVersionKeepsTheContextFilesOfTheOriginal() throws Exception {
        JsonNode upload = upload("context-" + UUID.randomUUID(), DIRTY);
        Path folder = Path.of(upload.get("filePath").asString());
        Files.writeString(folder.resolve("dataset_context.json"), "{\"domain\":\"Retail\"}");

        JsonNode applied = postJson(url(upload) + "/apply", "{\"table\":\"people\",\"operations\":[{\"type\":\"TRIM\",\"column\":\"name\"}]}", 200);

        assertThat(Files.readString(Path.of(applied.get("filePath").asString()).resolve("dataset_context.json"))).isEqualTo("{\"domain\":\"Retail\"}");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(String url) throws Exception {
        return json(mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode postJson(String url, String body, int expectedStatus) throws Exception {
        return json(mvc.perform(post(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(expectedStatus)).andReturn());
    }

    private JsonNode upload(String name, String csv) throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "people.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        return json(mvc.perform(multipart("/api/datasets/upload").file(file).param("name", name)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andReturn());
    }

    private String url(JsonNode upload) {
        return "/api/datasets/" + upload.get("datasetId").asLong() + "/versions/" + upload.get("versionId").asLong() + "/corrections";
    }

    private static java.util.List<String> keys(JsonNode found) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        found.get("suggestions").forEach(item -> keys.add(item.get("key").asString()));
        return keys;
    }

    private static JsonNode suggestion(JsonNode found, String key) {
        for (JsonNode item : found.get("suggestions")) {
            if (item.get("key").asString().equals(key)) {
                return item;
            }
        }
        throw new AssertionError("No suggestion " + key);
    }
}
