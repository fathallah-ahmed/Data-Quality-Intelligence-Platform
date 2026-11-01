package wevioo.backend.graph;

import org.springframework.stereotype.Component;
import wevioo.backend.correction.OllamaClient;
import wevioo.backend.graph.GraphModel.*;
import wevioo.backend.service.JsonService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Names the parts of the graph in the vocabulary of the user's domain. The structure (which nodes exist,
 * their keys and properties, which relationships join them) comes from the data and is never the model's
 * to change: it may only rename labels and relationship types, and describe each node. Any other
 * suggestion is ignored, and a name that is not a safe label is refused.
 */
@Component
public class GraphNamer {
    static final Pattern LABEL = Pattern.compile("^[A-Z][A-Za-z0-9]{0,39}$");
    static final Pattern TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,50}$");
    private static final int MAX_DESCRIPTION = 200;

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "nodes", Map.of("type", "array", "items", Map.of("type", "object", "properties", Map.of(
                "label", Map.of("type", "string"), "newLabel", Map.of("type", "string"), "description", Map.of("type", "string")))),
            "relationships", Map.of("type", "array", "items", Map.of("type", "object", "properties", Map.of(
                "type", Map.of("type", "string"), "newType", Map.of("type", "string"))))
        ),
        "required", List.of("nodes")
    );

    private final OllamaClient ollama;
    private final JsonService json;

    public GraphNamer(OllamaClient ollama, JsonService json) {
        this.ollama = ollama;
        this.json = json;
    }

    public Schema refine(Schema schema, Map<String, String> context) {
        String answer = ollama.generateJson(prompt(schema, context), SCHEMA);
        Map<String, Object> parsed = json.toMap(jsonObjectIn(answer));
        Map<String, String> renames = new LinkedHashMap<>();
        Map<String, String> descriptions = new LinkedHashMap<>();
        Set<String> labels = new HashSet<>();
        schema.nodes().forEach(n -> labels.add(n.label()));

        for (Object item : list(parsed.get("nodes"))) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            String label = text(entry.get("label"));
            if (schema.nodes().stream().noneMatch(n -> n.label().equals(label))) {
                continue;
            }
            String description = text(entry.get("description"));
            if (!description.isEmpty()) {
                descriptions.put(label, description.length() > MAX_DESCRIPTION ? description.substring(0, MAX_DESCRIPTION - 1) + "…" : description);
            }
            String renamed = text(entry.get("newLabel"));
            if (!renamed.isEmpty() && !renamed.equals(label) && LABEL.matcher(renamed).matches() && !labels.contains(renamed)) {
                labels.remove(label);
                labels.add(renamed);
                renames.put("label:" + label, renamed);
            }
        }
        for (Object item : list(parsed.get("relationships"))) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            String type = text(entry.get("type"));
            String renamed = text(entry.get("newType"));
            if (schema.relationships().stream().anyMatch(r -> r.type().equals(type))
                && !renamed.isEmpty() && !renamed.equals(type) && TYPE.matcher(renamed).matches()) {
                renames.put("type:" + type, renamed);
            }
        }
        List<NodeType> described = schema.nodes().stream()
            .map(n -> new NodeType(n.label(), n.table(), n.keyColumn(), n.derived(), n.origin(), descriptions.get(n.label()), n.properties()))
            .toList();
        return applyRenames(new Schema(described, schema.relationships(), schema.findings(), schema.promotable(), schema.renames()), renames);
    }

    /**
     * Renames labels ("label:Customer" → "Client") and relationship types ("type:HAS_ACCOUNT" → "OWNS").
     * Names are checked here too, because the same map comes back from the browser when a build is asked for.
     */
    public static Schema applyRenames(Schema schema, Map<String, String> renames) {
        if (renames == null || renames.isEmpty()) {
            return schema;
        }
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        renames.forEach((key, value) -> {
            if (key.startsWith("label:") && schema.nodes().stream().anyMatch(n -> n.label().equals(key.substring(6)))) {
                if (value == null || !LABEL.matcher(value).matches()) {
                    throw new IllegalArgumentException("Not a valid node name: " + value);
                }
                labels.put(key.substring(6), value);
            } else if (key.startsWith("type:") && schema.relationships().stream().anyMatch(r -> r.type().equals(key.substring(5)))) {
                if (value == null || !TYPE.matcher(value).matches()) {
                    throw new IllegalArgumentException("Not a valid relationship name: " + value);
                }
                types.put(key.substring(5), value);
            }
        });
        List<NodeType> nodes = schema.nodes().stream()
            .map(n -> new NodeType(labels.getOrDefault(n.label(), n.label()), n.table(), n.keyColumn(), n.derived(), n.origin(), n.description(), n.properties()))
            .toList();
        Set<String> seen = new HashSet<>();
        for (NodeType node : nodes) {
            if (!seen.add(node.label())) {
                throw new IllegalArgumentException("Two nodes would be named " + node.label());
            }
        }
        List<RelType> relationships = schema.relationships().stream()
            .map(r -> new RelType(types.getOrDefault(r.type(), r.type()), labels.getOrDefault(r.fromLabel(), r.fromLabel()),
                labels.getOrDefault(r.toLabel(), r.toLabel()), r.table(), r.fromColumn(), r.toColumn(), r.basis(), r.fromByPosition(), r.toByPosition()))
            .toList();
        Map<String, String> all = new LinkedHashMap<>(schema.renames());
        labels.forEach((from, to) -> all.put("label:" + from, to));
        types.forEach((from, to) -> all.put("type:" + from, to));
        return new Schema(nodes, relationships, schema.findings(), schema.promotable(), all);
    }

    private String prompt(Schema schema, Map<String, String> context) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (NodeType node : schema.nodes()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", node.label());
            entry.put("source_table", node.table());
            entry.put("identified_by", node.keyColumn());
            entry.put("why", node.origin());
            entry.put("properties", node.properties().stream().map(Property::column).toList());
            nodes.add(entry);
        }
        List<Map<String, Object>> relationships = new ArrayList<>();
        for (RelType relationship : schema.relationships()) {
            relationships.add(Map.of("type", relationship.type(), "from", relationship.fromLabel(), "to", relationship.toLabel(), "why", relationship.basis()));
        }
        return """
            You name the parts of a graph. Its structure comes from the data and is fixed: you only choose names.

            DOMAIN: %s
            GOAL OF THE ANALYSIS: %s
            ONE ROW REPRESENTS: %s

            NODES: %s
            RELATIONSHIPS: %s

            RULES:
            - For each node, give "newLabel" (PascalCase, letters and digits only, in the vocabulary of the domain; keep the current label if it is already right) and "description" (one sentence saying what a node stands for).
            - For each relationship, give "newType" (UPPER_SNAKE_CASE, a verb phrase such as OWNS_ACCOUNT; keep the current type if it is already right).
            - Do not add, remove or merge nodes or relationships and do not mention properties that are not listed.
            - Two nodes must not get the same label.
            - Return only JSON: {"nodes": [{"label", "newLabel", "description"}], "relationships": [{"type", "newType"}]}.
            """.formatted(
            context.getOrDefault("domain", "Not provided"), context.getOrDefault("objective", "Not provided"),
            context.getOrDefault("row_grain", "Not provided"), json.toJson(nodes), json.toJson(relationships));
    }

    private static String jsonObjectIn(String answer) {
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        return start >= 0 && end > start ? answer.substring(start, end + 1) : answer;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
