package wevioo.backend.service;

import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import wevioo.backend.config.AppProperties;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Service
public class AirflowClient {
    private final AppProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();

    public AirflowClient(AppProperties properties) {
        this.properties = properties;
    }

    public TriggerResult triggerPipeline(
        String runId,
        String datasetId,
        String versionId,
        String filePath
    ) {
        String url = properties.getAirflowBaseUrl().replaceAll("/$", "")
            + "/api/v1/dags/" + properties.getAirflowDagId() + "/dagRuns";

        Map<String, Object> body = Map.of(
            "dag_run_id", runId,
            "conf", Map.of(
                "dataset_id", datasetId,
                "version_id", versionId,
                "pipeline_run_id", runId,
                "file_path", filePath,
                "backend_url", properties.getPipelineBackendUrl(),
                "debug", true
            )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String credentials = properties.getAirflowUsername() + ":" + properties.getAirflowPassword();
        headers.set("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            return new TriggerResult(response.getStatusCode().is2xxSuccessful(), "Airflow response: " + response.getStatusCode());
        } catch (RestClientException e) {
            return new TriggerResult(false, e.getMessage());
        }
    }

    public record TriggerResult(boolean triggered, String message) {}
}
