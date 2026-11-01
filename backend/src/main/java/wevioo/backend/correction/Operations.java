package wevioo.backend.correction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The corrections a user can ask for. Only these operations exist: a person's sentence (or the model's
 * reading of it) is turned into a list of them and checked here, so nothing can run that is not on the
 * list, and every operation can be previewed and explained.
 */
public final class Operations {
    /** Operation types and whether they apply to one column. */
    private static final Map<String, Boolean> TYPES = Map.ofEntries(
        Map.entry("TRIM", true),
        Map.entry("COLLAPSE_SPACES", true),
        Map.entry("CASE", true),
        Map.entry("REPLACE_VALUES", true),
        Map.entry("REPLACE_TEXT", true),
        Map.entry("EMPTY_TO_NULL", true),
        Map.entry("FILL_EMPTY", true),
        Map.entry("NORMALIZE_DATE", true),
        Map.entry("DIGITS_ONLY", true),
        Map.entry("PAD_LEFT", true),
        Map.entry("DROP_EMPTY_ROWS", true),
        Map.entry("DROP_DUPLICATE_ROWS", false)
    );
    public static final List<String> EMPTY_TOKENS = List.of("N/A", "NA", "#N/A", "null", "none", "nan", "-", "--", "?", "missing");
    private static final Set<String> CASE_MODES = Set.of("UPPER", "LOWER", "TITLE");
    private static final Set<String> DATE_ORDERS = Set.of("AUTO", "DMY", "MDY", "YMD");
    public static final int MAX_MAPPINGS = 500;

    private Operations() {}

    /** One operation: its type, the column it applies to (none for a whole-row operation) and its settings. */
    public record OpSpec(String type, String column, Map<String, Object> params) {
        public Map<String, Object> settings() {
            return params == null ? Map.of() : params;
        }
    }

    public static boolean isKnown(String type) {
        return type != null && TYPES.containsKey(type.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Check an operation against the columns of the table and return it in its normal form (real column
     * name, settings filled in). Throws {@link IllegalArgumentException} with a reason a person can read.
     */
    public static OpSpec validate(OpSpec raw, List<String> columns) {
        if (raw == null || raw.type() == null || !isKnown(raw.type())) {
            throw new IllegalArgumentException("Unknown correction: " + (raw == null ? null : raw.type()));
        }
        String type = raw.type().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> settings = raw.settings();
        Map<String, Object> clean = new LinkedHashMap<>();
        String column = null;
        if (TYPES.get(type)) {
            column = resolveColumn(raw.column(), columns, type);
        }
        switch (type) {
            case "CASE" -> {
                String mode = text(settings.get("mode")).toUpperCase(Locale.ROOT);
                if (!CASE_MODES.contains(mode)) {
                    throw new IllegalArgumentException("CASE needs mode UPPER, LOWER or TITLE");
                }
                clean.put("mode", mode);
            }
            case "REPLACE_VALUES" -> {
                List<Map<String, String>> mappings = new ArrayList<>();
                if (settings.get("mappings") instanceof List<?> items) {
                    for (Object item : items) {
                        if (item instanceof Map<?, ?> entry && entry.get("from") != null && entry.get("to") != null) {
                            mappings.add(Map.of("from", String.valueOf(entry.get("from")), "to", String.valueOf(entry.get("to"))));
                        }
                    }
                }
                if (mappings.isEmpty()) {
                    throw new IllegalArgumentException("REPLACE_VALUES needs at least one {from, to} pair");
                }
                if (mappings.size() > MAX_MAPPINGS) {
                    throw new IllegalArgumentException("REPLACE_VALUES is limited to " + MAX_MAPPINGS + " pairs");
                }
                clean.put("mappings", mappings);
                clean.put("ignoreCase", Boolean.TRUE.equals(settings.get("ignoreCase")) || "true".equalsIgnoreCase(text(settings.get("ignoreCase"))));
            }
            case "REPLACE_TEXT" -> {
                String find = text(settings.get("find"));
                if (find.isEmpty() || find.length() > 100) {
                    throw new IllegalArgumentException("REPLACE_TEXT needs the text to find (1 to 100 characters)");
                }
                clean.put("find", find);
                clean.put("with", settings.get("with") == null ? "" : String.valueOf(settings.get("with")));
            }
            case "EMPTY_TO_NULL" -> {
                List<String> tokens = new ArrayList<>();
                if (settings.get("tokens") instanceof List<?> items) {
                    items.forEach(item -> tokens.add(String.valueOf(item)));
                }
                clean.put("tokens", tokens.isEmpty() ? EMPTY_TOKENS : tokens.stream().limit(50).toList());
            }
            case "FILL_EMPTY" -> {
                Object value = settings.get("value");
                if (value == null || String.valueOf(value).isBlank()) {
                    throw new IllegalArgumentException("FILL_EMPTY needs the value to put in empty cells");
                }
                clean.put("value", String.valueOf(value));
            }
            case "NORMALIZE_DATE" -> {
                String order = text(settings.get("order")).isEmpty() ? "AUTO" : text(settings.get("order")).toUpperCase(Locale.ROOT);
                if (!DATE_ORDERS.contains(order)) {
                    throw new IllegalArgumentException("NORMALIZE_DATE order must be AUTO, DMY, MDY or YMD");
                }
                clean.put("order", order);
            }
            case "PAD_LEFT" -> {
                int width = integer(settings.get("width"));
                String pad = text(settings.get("char")).isEmpty() ? "0" : text(settings.get("char"));
                if (width < 1 || width > 50 || pad.length() != 1) {
                    throw new IllegalArgumentException("PAD_LEFT needs a width from 1 to 50 and one padding character");
                }
                clean.put("width", width);
                clean.put("char", pad);
            }
            default -> { /* no settings */ }
        }
        return new OpSpec(type, column, clean);
    }

    private static String resolveColumn(String requested, List<String> columns, String type) {
        if (requested == null || requested.isBlank()) {
            throw new IllegalArgumentException(type + " needs a column");
        }
        String wanted = requested.trim();
        for (String column : columns) {
            if (column.equals(wanted)) {
                return column;
            }
        }
        for (String column : columns) {
            if (column.equalsIgnoreCase(wanted)) {
                return column;
            }
        }
        throw new IllegalArgumentException("The table has no column named " + wanted);
    }

    /** The operation in words, for the preview and the log. */
    public static String describe(OpSpec op) {
        Map<String, Object> s = op.settings();
        String column = op.column();
        return switch (op.type()) {
            case "TRIM" -> "Remove spaces around the values of " + column;
            case "COLLAPSE_SPACES" -> "Reduce repeated spaces to one in " + column;
            case "CASE" -> "Write " + column + " in " + switch (String.valueOf(s.get("mode"))) {
                case "UPPER" -> "capital letters";
                case "LOWER" -> "lower case";
                default -> "title case";
            };
            case "REPLACE_VALUES" -> "Replace " + ((List<?>) s.get("mappings")).size() + " value(s) in " + column
                + (Boolean.TRUE.equals(s.get("ignoreCase")) ? " (ignoring case)" : "");
            case "REPLACE_TEXT" -> "In " + column + ", replace '" + s.get("find") + "' with '" + s.get("with") + "'";
            case "EMPTY_TO_NULL" -> "Empty the placeholders (" + String.join(", ", ((List<?>) s.get("tokens")).stream().limit(6).map(String::valueOf).toList()) + ") in " + column;
            case "FILL_EMPTY" -> "Put '" + s.get("value") + "' in the empty cells of " + column;
            case "NORMALIZE_DATE" -> "Write the dates of " + column + " as yyyy-MM-dd"
                + ("AUTO".equals(s.get("order")) ? " (ambiguous ones are left for review)" : " (read as " + s.get("order") + ")");
            case "DIGITS_ONLY" -> "Keep only the digits of " + column;
            case "PAD_LEFT" -> "Pad " + column + " to " + s.get("width") + " characters with '" + s.get("char") + "' on the left";
            case "DROP_EMPTY_ROWS" -> "Remove the rows where " + column + " is empty";
            case "DROP_DUPLICATE_ROWS" -> "Remove rows that are an exact copy of an earlier row";
            default -> op.type();
        };
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
