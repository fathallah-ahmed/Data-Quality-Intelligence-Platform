package wevioo.backend.service;

import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import wevioo.backend.config.AppProperties;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Service
public class AirflowClient {
    private final AppProperties properties;
    private final RestTemplate restTemplate;

    public AirflowClient(AppProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getAirflowConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getAirflowReadTimeoutMs());
        this.restTemplate = new RestTemplate(requestFactory);
    }

    public TriggerResult triggerPipeline(
        String runId,
        String datasetId,
        String versionId,
        String filePath
    ) {
        Map<String, Object> body = Map.of(
            "dag_run_id", runId,
            "conf", Map.of(
                "dataset_id", datasetId,
                "version_id", versionId,
                "pipeline_run_id", runId,
                "file_path", filePath,
                "backend_url", properties.getPipelineBackendUrl(),
                "debug", properties.isAirflowDebug()
            )
        );

        try {
            ResponseEntity<String> response = restTemplate.exchange(dagRunsUrl(), HttpMethod.POST, new HttpEntity<>(body, headers()), String.class);
            return new TriggerResult(response.getStatusCode().is2xxSuccessful(), "Airflow accepted the run (" + response.getStatusCode().value() + ")");
        } catch (HttpStatusCodeException e) {
            return new TriggerResult(false, "Airflow refused the run: HTTP " + e.getStatusCode().value() + hint(e.getStatusCode().value()));
        } catch (RestClientException e) {
            return new TriggerResult(false, "Airflow is not reachable at " + properties.getAirflowBaseUrl() + " (" + rootMessage(e) + ")");
        }
    }

    /** Returns the Airflow state of a DAG run: queued, running, success, failed, NOT_FOUND, or UNREACHABLE. */
    public String getDagRunState(String runId) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                dagRunsUrl() + "/" + runId,
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                Map.class
            );
            Object state = response.getBody() == null ? null : response.getBody().get("state");
            return state == null ? "UNKNOWN" : String.valueOf(state);
        } catch (HttpStatusCodeException e) {
            return e.getStatusCode().value() == 404 ? NOT_FOUND : UNREACHABLE;
        } catch (RestClientException e) {
            return UNREACHABLE;
        }
    }

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String UNREACHABLE = "UNREACHABLE";

    private String dagRunsUrl() {
        return properties.getAirflowBaseUrl().replaceAll("/$", "")
            + "/api/v1/dags/" + properties.getAirflowDagId() + "/dagRuns";
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String credentials = properties.getAirflowUsername() + ":" + properties.getAirflowPassword();
        headers.set("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        return headers;
    }

    private String hint(int status) {
        return switch (status) {
            case 401, 403 -> ". Check AIRFLOW_USERNAME / AIRFLOW_PASSWORD.";
            case 404 -> ". Is the DAG '" + properties.getAirflowDagId() + "' deployed?";
            case 409 -> ". A run with this id already exists.";
            default -> ".";
        };
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record TriggerResult(boolean triggered, String message) {}
}
