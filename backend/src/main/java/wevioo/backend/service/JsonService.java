package wevioo.backend.service;

import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Map;
import java.nio.file.Path;

@Service
public class JsonService {
    private final ObjectMapper mapper;

    public JsonService(ObjectMapper baseMapper) {
        this.mapper = baseMapper;
    }

    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialize JSON payload", e);
        }
    }

    public Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    public Map<String, Object> readMap(Path path) {
        if (path == null) {
            return Collections.emptyMap();
        }
        try {
            return mapper.readValue(path.toFile(), new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    public String summaryFromInterpretation(Map<String, Object> interpretation) {
        if (interpretation == null) {
            return null;
        }
        Object direct = interpretation.get("summary");
        if (direct != null) {
            return String.valueOf(direct);
        }
        Object nested = interpretation.get("interpretation");
        if (nested instanceof Map<?, ?> nestedMap && nestedMap.get("summary") != null) {
            return String.valueOf(nestedMap.get("summary"));
        }
        return null;
    }
}
