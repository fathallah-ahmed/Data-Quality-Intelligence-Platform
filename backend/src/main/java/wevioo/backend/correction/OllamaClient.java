package wevioo.backend.correction;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import wevioo.backend.config.AppProperties;

import java.util.Map;

/** Asks the local Ollama model for a JSON answer. Unreachable or unconfigured means {@link LlmUnavailableException}. */
@Service
public class OllamaClient {
    /** The model could not be asked: not configured, not running, too slow or it refused. */
    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message) {
            super(message);
        }
    }

    private final AppProperties properties;
    private final RestTemplate restTemplate;

    public OllamaClient(AppProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(properties.getOllamaTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
    }

    /** @param schema a JSON schema the answer must follow (Ollama structured output) */
    public String generateJson(String prompt, Map<String, Object> schema) {
        String model = properties.getOllamaModel();
        if (model == null || model.isBlank()) {
            throw new LlmUnavailableException("No language model is configured: set OLLAMA_MODEL for the backend.");
        }
        String base = properties.getOllamaUrl().replaceAll("/$", "");
        Map<String, Object> body = Map.of(
            "model", model,
            "prompt", prompt,
            "stream", false,
            "think", false,
            "format", schema,
            "options", Map.of("temperature", 0.1, "top_p", 0.9, "num_ctx", 16384, "num_predict", 3072)
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<Map> response = restTemplate.exchange(base + "/api/generate", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            Object text = response.getBody() == null ? null : response.getBody().get("response");
            if (text == null || String.valueOf(text).isBlank()) {
                throw new LlmUnavailableException("The model returned an empty answer.");
            }
            return String.valueOf(text);
        } catch (HttpStatusCodeException e) {
            throw new LlmUnavailableException("The model refused the request: HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new LlmUnavailableException("The model is not reachable at " + base + " (is Ollama running?)");
        }
    }
}
