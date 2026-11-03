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
import wevioo.backend.graph.GraphStore;
import wevioo.backend.qa.QaClient;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import wevioo.backend.service.AirflowClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:qa-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.admin-username=admin",
    "app.admin-password=test-password",
    "app.pipeline-api-token=test-pipeline-token",
    "app.watchdog-interval-ms=3600000"
})
@AutoConfigureMockMvc
class QaApiTest {
    private static final String PIPELINE_TOKEN = "test-pipeline-token";

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
    @MockitoBean GraphStore graphStore;
    @MockitoBean QaClient qaClient;

    private String token;

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

    /** 60 accounts of 20 customers; customer C03 is given two emails. */
    private static String accountsCsv() {
        StringBuilder text = new StringBuilder("account_id,customer_id,customer_name,email,balance\n");
        for (int i = 0; i < 60; i++) {
            int customer = i % 20;
            String email = customer == 3 && i == 23 ? "other@mail.com" : "c" + customer + "@mail.com";
            text.append("A").append(i).append(",C").append(String.format("%02d", customer)).append(",Name ").append(customer)
                .append(",").append(email).append(",").append(100 + i).append("\n");
        }
        return text.toString();
    }

    private static String column(String name, String role, int distinct) {
        return "{\"name\":\"%s\",\"data_type\":\"object\",\"missing_count\":0,\"missing_percent\":0,\"distinct_count\":%d,\"role\":\"%s\",\"role_confidence\":0.9,\"role_source\":\"rules\"}"
            .formatted(name, distinct, role);
    }

    /** An uploaded and analysed version, with the roles the pipeline would have found. */
    private JsonNode analysedAccounts() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "accounts.csv", "text/csv", accountsCsv().getBytes(StandardCharsets.UTF_8));
        JsonNode upload = json(mvc.perform(multipart("/api/datasets/upload").file(file).param("name", "graph-" + UUID.randomUUID())
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn());
        String body = """
            {"table_name": "accounts", "status": "completed", "llm_status": "unavailable",
             "dataset_metrics": {"row_count": 60, "column_count": 5, "duplicate_rows": 0, "duplicate_percent": 0},
             "column_metrics": [%s, %s, %s, %s, %s],
             "data_signature": {"row_count": 60, "column_count": 5, "content_hash": "g1", "schema_hash": "s5"}}
            """.formatted(column("account_id", "IDENTIFIER", 60), column("customer_id", "REFERENCE", 20),
            column("customer_name", "NAME", 20), column("email", "EMAIL", 21), column("balance", "AMOUNT", 60));
        mvc.perform(post("/api/pipeline/" + upload.get("pipelineRunId").asString() + "/table-analysis")
                .header("X-Pipeline-Token", PIPELINE_TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        return upload;
    }

    private String url(JsonNode upload) {
        return "/api/datasets/" + upload.get("datasetId").asLong() + "/versions/" + upload.get("versionId").asLong() + "/graph";
    }

    // ------------------------------------------------------------------ the model of the graph

    private String base(JsonNode upload) {
        return "/api/datasets/" + upload.get("datasetId").asLong() + "/versions/" + upload.get("versionId").asLong();
    }

    private void build(JsonNode upload) throws Exception {
        postJson(base(upload) + "/graph/build", "{}", 200);
    }

    @Test
    void aQuestionNeedsTheGraphToBeBuiltFirst() throws Exception {
        JsonNode upload = analysedAccounts();

        JsonNode error = postJson(base(upload) + "/ask", "{\"question\": \"How many customers are there?\"}", 409);

        assertThat(error.get("error").asString()).contains("Build the graph");
        verifyNoInteractions(qaClient);
    }

    @Test
    void anEmptyOrTooLongQuestionIsRefusedBeforeTheServiceIsCalled() throws Exception {
        JsonNode upload = analysedAccounts();
        build(upload);

        assertThat(postJson(base(upload) + "/ask", "{\"question\": \"   \"}", 400).get("error").asString()).contains("Write a question");
        assertThat(postJson(base(upload) + "/ask", "{}", 400).get("error").asString()).contains("Write a question");
        String tooLong = "x".repeat(1001);
        assertThat(postJson(base(upload) + "/ask", "{\"question\": \"" + tooLong + "\"}", 400).get("error").asString()).contains("limited to");
        verifyNoInteractions(qaClient);
    }

    @Test
    void theQuestionIsSentWithItsScopeAndTheFactsOfTheVersion() throws Exception {
        JsonNode upload = analysedAccounts();
        build(upload);
        String scope = "d" + upload.get("datasetId").asLong() + "v" + upload.get("versionId").asLong();
        when(qaClient.ask(anyString(), anyString(), anyMap())).thenReturn(Map.of("answer", "20 customers", "cypher", "MATCH (c) RETURN count(c)"));

        JsonNode answer = postJson(base(upload) + "/ask", "{\"question\": \"  How many customers?  \"}", 200);

        assertThat(answer.get("answer").asString()).isEqualTo("20 customers");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> facts = ArgumentCaptor.forClass(Map.class);
        verify(qaClient).ask(eq("How many customers?"), eq(scope), facts.capture());
        assertThat(facts.getValue()).containsKeys("dataset", "version", "context", "rules", "dimensions", "findings", "columns", "graph");
        assertThat(facts.getValue().get("columns").toString()).contains("customer_id", "REFERENCE");
        assertThat(facts.getValue().get("graph").toString()).contains("Account", "Customer");
    }

    @Test
    void whenTheQuestionServiceIsDownTheUserIsToldSo() throws Exception {
        JsonNode upload = analysedAccounts();
        build(upload);
        when(qaClient.ask(anyString(), anyString(), anyMap())).thenThrow(new QaClient.QaUnavailableException("The question service is not reachable"));

        JsonNode error = postJson(base(upload) + "/ask", "{\"question\": \"Who?\"}", 503);

        assertThat(error.get("error").asString()).contains("not reachable");
    }

    @Test
    void aQuestionTheServiceRefusesComesBackAsABadRequest() throws Exception {
        JsonNode upload = analysedAccounts();
        build(upload);
        when(qaClient.ask(anyString(), anyString(), anyMap())).thenThrow(new IllegalArgumentException("That question would change data"));

        JsonNode error = postJson(base(upload) + "/ask", "{\"question\": \"Delete everything\"}", 400);

        assertThat(error.get("error").asString()).contains("would change data");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postJson(String url, String body, int expected) throws Exception {
        return json(mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(expected)).andReturn());
    }
}
