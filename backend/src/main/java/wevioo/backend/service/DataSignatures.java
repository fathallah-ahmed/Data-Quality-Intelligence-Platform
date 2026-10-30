package wevioo.backend.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Compares table data signatures produced by the pipeline to detect value-level changes. */
public final class DataSignatures {
    private static final List<String> LEGACY_KEYS = List.of("row_count", "column_count", "duplicate_rows", "columns");

    private DataSignatures() {}

    /**
     * Signatures with a {@code content_hash} are compared by content and schema hash, so editing a
     * single value is detected. Older signatures (counts only) fall back to comparing the counts.
     */
    public static boolean sameContent(Map<String, Object> current, Map<String, Object> previous) {
        if (current == null || previous == null || current.isEmpty() || previous.isEmpty()) {
            return false;
        }
        Object currentHash = current.get("content_hash");
        Object previousHash = previous.get("content_hash");
        if (currentHash != null && previousHash != null) {
            return currentHash.equals(previousHash)
                && Objects.equals(current.get("schema_hash"), previous.get("schema_hash"));
        }
        for (String key : LEGACY_KEYS) {
            if (!Objects.equals(normalize(current.get(key)), normalize(previous.get(key)))) {
                return false;
            }
        }
        return true;
    }

    /** JSON numbers may come back as Integer or Long; compare them by value. */
    private static Object normalize(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(DataSignatures::normalize).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new TreeMap<>();
            map.forEach((key, item) -> normalized.put(String.valueOf(key), normalize(item)));
            return normalized;
        }
        return value;
    }
}
