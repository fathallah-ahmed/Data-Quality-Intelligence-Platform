package wevioo.backend.correction;

import org.springframework.stereotype.Component;
import wevioo.backend.correction.Operations.OpSpec;
import wevioo.backend.correction.TableScanner.ColumnSample;
import wevioo.backend.correction.TableScanner.Scan;
import wevioo.backend.service.JsonService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a request written in plain language into corrections. The model only proposes; every operation it
 * returns is checked against the table's columns and the list of allowed operations, and the ones that fail
 * are reported instead of run. The user then previews the result before anything is written.
 */
@Component
public class CorrectionPlanner {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CorrectionPlanner.class);
    private static final int MAX_OPERATIONS = 20;

    /** @param assumptions what the model assumed (for example how dates are written), shown to the user */
    public record Plan(List<OpSpec> operations, String assumptions, List<String> rejected) {}

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "operations", Map.of("type", "array", "items", Map.of(
                "type", "object",
                "properties", Map.of(
                    "type", Map.of("type", "string"),
                    "column", Map.of("type", "string"),
                    "params", Map.of("type", "object")
                ),
                "required", List.of("type")
            )),
            "assumptions", Map.of("type", "string")
        ),
        "required", List.of("operations")
    );

    private final OllamaClient ollama;
    private final JsonService json;

    public CorrectionPlanner(OllamaClient ollama, JsonService json) {
        this.ollama = ollama;
        this.json = json;
    }

    public Plan plan(String table, Scan scan, String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("Write what you want to correct");
        }
        String answer = ollama.generateJson(prompt(table, scan, instruction.strip()), SCHEMA);
        Map<String, Object> parsed = json.toMap(jsonObjectIn(answer));
        if (parsed.isEmpty()) {
            log.warn("The model's answer to a correction request was not a JSON object: {}", answer.length() > 600 ? answer.substring(0, 600) + "…" : answer);
        }
        List<OpSpec> operations = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        Object items = parsed.get("operations");
        if (parsed.isEmpty() || !(items instanceof List<?>)) {
            rejected.add("The model did not answer with a list of operations. Try to say it differently.");
        } else {
            for (Object item : (List<?>) items) {
                if (operations.size() >= MAX_OPERATIONS) {
                    rejected.add("Only the first " + MAX_OPERATIONS + " operations are kept.");
                    break;
                }
                try {
                    operations.add(Operations.validate(toSpec(item), scan.columns()));
                } catch (IllegalArgumentException e) {
                    rejected.add(e.getMessage());
                }
            }
        }
        Object assumptions = parsed.get("assumptions");
        return new Plan(operations, assumptions == null ? "" : String.valueOf(assumptions).strip(), rejected);
    }

    /** The JSON object in an answer, even when the model wrapped it in a code block or added a sentence. */
    static String jsonObjectIn(String answer) {
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        return start >= 0 && end > start ? answer.substring(start, end + 1) : answer;
    }

    @SuppressWarnings("unchecked")
    private static OpSpec toSpec(Object item) {
        if (!(item instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("An operation is not an object");
        }
        Object params = map.get("params");
        return new OpSpec(
            map.get("type") == null ? null : String.valueOf(map.get("type")),
            map.get("column") == null ? null : String.valueOf(map.get("column")),
            params instanceof Map<?, ?> ? (Map<String, Object>) params : Map.of()
        );
    }

    String prompt(String table, Scan scan, String instruction) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (ColumnSample sample : scan.samples()) {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", sample.name());
            column.put("filled_values", sample.filled());
            column.put("distinct_values", sample.distinct());
            column.put("most_frequent", sample.topValues().stream().map(v -> v.value() + " (" + v.count() + ")").toList());
            columns.add(column);
        }
        return """
            You turn a data owner's request into corrections for ONE table. You only choose from the operations below.

            TABLE: %s (%d rows)
            REQUEST: %s

            OPERATIONS (JSON: {"type", "column", "params"}; DROP_DUPLICATE_ROWS has no column):
            - TRIM: remove spaces around values.
            - COLLAPSE_SPACES: reduce repeated spaces to one.
            - CASE: params {"mode": "UPPER" | "LOWER" | "TITLE"}.
            - REPLACE_VALUES: params {"mappings": [{"from": "...", "to": "..."}], "ignoreCase": true|false}. Exact whole values: typos, one country written several ways.
            - REPLACE_TEXT: params {"find": "...", "with": "..."}. Part of a value, such as a unit or a prefix.
            - EMPTY_TO_NULL: params {"tokens": ["N/A", "-"]}. Placeholders become empty.
            - FILL_EMPTY: params {"value": "..."}. Put a value in empty cells.
            - NORMALIZE_DATE: params {"order": "AUTO" | "DMY" | "MDY" | "YMD"}. Writes dates as yyyy-MM-dd. Use AUTO unless the request says how the dates are written; AUTO leaves ambiguous ones (01/02/2024) for review.
            - DIGITS_ONLY: keep only digits (and a leading +).
            - PAD_LEFT: params {"width": 5, "char": "0"}. For codes that lost leading zeros.
            - DROP_EMPTY_ROWS: remove rows where the column is empty.
            - DROP_DUPLICATE_ROWS: remove rows that are an exact copy of an earlier one.

            HARD RULES:
            - Use only the exact column names below. Never invent a column.
            - Never invent values: a replacement must come from the request, or from a value listed under most_frequent.
            - Do not guess how an ambiguous date is written; if the request does not say, use AUTO and say so in "assumptions".
            - If the request cannot be done with these operations, return "operations": [] and explain why in "assumptions".
            - Return only JSON: {"operations": [...], "assumptions": "one or two sentences"}.

            COLUMNS:
            %s
            """.formatted(table, scan.rows(), instruction, json.toJson(columns));
    }
}
