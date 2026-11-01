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
    "spring.datasource.url=jdbc:h2:mem:graph-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.admin-username=admin",
    "app.admin-password=test-password",
    "app.pipeline-api-token=test-pipeline-token",
    "app.watchdog-interval-ms=3600000"
})
@AutoConfigureMockMvc
class GraphApiTest {
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

    @Test
    void theModelFindsTheCustomerInsideTheAccountsAndReportsTheConflict() throws Exception {
        JsonNode upload = analysedAccounts();

        JsonNode schema = getJson(url(upload) + "/schema", 200);

        assertThat(labels(schema)).containsExactly("Account", "Customer");
        JsonNode customer = schema.get("nodes").get(1);
        assertThat(customer.get("derived").asBoolean()).isTrue();
        assertThat(customer.get("keyColumn").asString()).isEqualTo("customer_id");
        assertThat(customer.get("properties").toString()).contains("customer_name", "email");
        assertThat(schema.get("relationships").get(0).get("type").asString()).isEqualTo("HAS_ACCOUNT");
        assertThat(schema.get("findings").toString()).contains("DEPENDENCY", "customer_id → email");
        verifyNoInteractions(graphStore);   // proposing a model writes nothing
    }

    @Test
    void theModelCanBeNamedByTheLocalModelButOnlyWithSafeNames() throws Exception {
        JsonNode upload = analysedAccounts();
        when(ollama.generateJson(anyString(), anyMap())).thenReturn("""
            {"nodes": [{"label": "Customer", "newLabel": "Client", "description": "A person who holds accounts."},
                       {"label": "Account", "newLabel": "Account; DROP", "description": "A bank account."},
                       {"label": "Ghost", "newLabel": "Spirit"}],
             "relationships": [{"type": "HAS_ACCOUNT", "newType": "OWNS_ACCOUNT"}, {"type": "HAS_ACCOUNT", "newType": "owns account"}]}
            """);

        JsonNode schema = getJson(url(upload) + "/schema?refine=true", 200);

        assertThat(labels(schema)).containsExactly("Account", "Client");                 // the unsafe and the invented names are ignored
        assertThat(schema.get("nodes").get(1).get("description").asString()).isEqualTo("A person who holds accounts.");
        assertThat(schema.get("relationships").get(0).get("type").asString()).isEqualTo("OWNS_ACCOUNT");
        assertThat(schema.get("relationships").get(0).get("fromLabel").asString()).isEqualTo("Client");
        assertThat(schema.get("renames").get("label:Customer").asString()).isEqualTo("Client");
    }

    // ------------------------------------------------------------------ the build

    @Test
    void buildingWritesTheGraphAndRemembersTheResult() throws Exception {
        JsonNode upload = analysedAccounts();
        String scope = "d" + upload.get("datasetId").asLong() + "v" + upload.get("versionId").asLong();

        JsonNode build = postJson(url(upload) + "/build", "{\"promote\": [], \"renames\": {\"label:Customer\": \"Client\"}}", 200);

        assertThat(build.get("result").get("scope").asString()).isEqualTo(scope);
        assertThat(build.get("result").get("nodes").get("Account").asLong()).isEqualTo(60);
        assertThat(build.get("result").get("nodes").get("Client").asLong()).isEqualTo(20);
        assertThat(build.get("result").get("findings").toString()).contains("PROPERTY_CONFLICT", "Client.email: 1 node(s)");
        verify(graphStore).ping();
        verify(graphStore).clear(scope);
        verify(graphStore).writeNodes(eq(scope), eq("Client"), anyList());
        verify(graphStore).writeRelationships(eq(scope), eq("HAS_ACCOUNT"), eq("Client"), eq("Account"), anyList());

        JsonNode last = getJson(url(upload), 200);
        assertThat(last.get("result").get("nodes").get("Client").asLong()).isEqualTo(20);
        assertThat(Files.exists(Path.of(upload.get("filePath").asString()).resolve("graph_build.json"))).isTrue();
    }

    @Test
    void anInvalidNameIsRefusedBeforeAnythingIsWritten() throws Exception {
        JsonNode upload = analysedAccounts();

        JsonNode error = postJson(url(upload) + "/build", "{\"renames\": {\"label:Customer\": \"Client`) DETACH DELETE (n\"}}", 400);

        assertThat(error.get("error").asString()).contains("Not a valid node name");
        verify(graphStore, never()).writeNodes(anyString(), anyString(), anyList());
    }

    @Test
    void whenNeo4jIsDownTheBuildSaysSoAndTheStatusReportsIt() throws Exception {
        JsonNode upload = analysedAccounts();
        doThrow(new GraphStore.GraphUnavailableException("Neo4j cannot be reached")).when(graphStore).ping();

        JsonNode error = postJson(url(upload) + "/build", "{}", 503);
        JsonNode status = getJson(url(upload) + "/status", 200);

        assertThat(error.get("error").asString()).isEqualTo("Neo4j cannot be reached");
        assertThat(status.get("available").asBoolean()).isFalse();
        assertThat(status.get("browserUrl").asString()).startsWith("http://");
        verify(graphStore, never()).writeNodes(anyString(), anyString(), anyList());
    }

    @Test
    void aVersionNotAnalysedYetHasNothingToBuildFrom() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "t.csv", "text/csv", "a\n1\n".getBytes(StandardCharsets.UTF_8));
        JsonNode upload = json(mvc.perform(multipart("/api/datasets/upload").file(file).param("name", "empty-" + UUID.randomUUID())
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn());

        JsonNode error = getJson(url(upload) + "/schema", 400);

        assertThat(error.get("error").asString()).contains("no analysed table yet");
    }

    @Test
    void deletingTheDatasetRemovesItsGraphEvenWhenNeo4jFails() throws Exception {
        JsonNode upload = analysedAccounts();
        mvc.perform(post("/api/pipeline/" + upload.get("pipelineRunId").asString() + "/complete")
            .header("X-Pipeline-Token", PIPELINE_TOKEN).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"success\",\"failed_tables\":[]}")).andExpect(status().isOk());
        doThrow(new GraphStore.GraphUnavailableException("down")).when(graphStore).clearPrefix(anyString());

        mvc.perform(delete("/api/datasets/" + upload.get("datasetId").asLong()).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());

        verify(graphStore).clearPrefix("d" + upload.get("datasetId").asLong() + "v");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(String url, int expected) throws Exception {
        return json(mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().is(expected)).andReturn());
    }

    private JsonNode postJson(String url, String body, int expected) throws Exception {
        return json(mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(expected)).andReturn());
    }

    private static java.util.List<String> labels(JsonNode schema) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        schema.get("nodes").forEach(node -> labels.add(node.get("label").asString()));
        return labels;
    }
}
