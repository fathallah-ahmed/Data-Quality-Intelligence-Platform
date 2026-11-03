package wevioo.backend.qa;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import wevioo.backend.config.AppProperties;
import wevioo.backend.service.JsonService;

import java.util.Map;

/** Talks to the question service (Python). The service answers; this class only carries the question and the facts. */
@Service
public class QaClient {
    /** The question service cannot answer: not running, no model, Neo4j down. The message is meant for the user. */
    public static class QaUnavailableException extends RuntimeException {
        public QaUnavailableException(String message) {
            super(message);
        }
    }

    private final AppProperties properties;
    private final JsonService json;
    private final RestTemplate restTemplate;

    public QaClient(AppProperties properties, JsonService json) {
        this.properties = properties;
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(properties.getQaTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> ask(String question, String scope, Map<String, Object> facts) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-QA-Token", properties.getQaToken());
        String base = properties.getQaUrl().replaceAll("/$", "");
        try {
            return restTemplate.exchange(base + "/ask", HttpMethod.POST,
                new HttpEntity<>(Map.of("question", question, "scope", scope, "facts", facts), headers), Map.class).getBody();
        } catch (HttpStatusCodeException e) {
            String detail = detail(e.getResponseBodyAsString());
            if (e.getStatusCode().is4xxClientError() && e.getStatusCode().value() != 401) {
                throw new IllegalArgumentException(detail);
            }
            throw new QaUnavailableException(e.getStatusCode().value() == 401
                ? "The question service refused the backend's token (QA_API_TOKEN differs between the two)."
                : detail);
        } catch (RestClientException e) {
            throw new QaUnavailableException("The question service is not reachable at " + base
                + " (is it running? see graphrag/README or start-dev.ps1).");
        }
    }

    private String detail(String body) {
        Object detail = json.toMap(body).get("detail");
        return detail == null ? "The question service could not answer." : String.valueOf(detail);
    }
}
