package wevioo.backend.service;

import java.util.List;
import java.util.Map;

/** Reads the column-against-column measurements the pipeline stores with a table analysis. */
final class CrossChecks {
    private CrossChecks() {}

    /**
     * How often {@code first} comes after {@code second} on the rows where both dates are filled.
     *
     * @param violations rows where {@code first} is after {@code second}
     * @param compared   rows where both dates are filled
     * @param examples   a few offending rows, as text
     */
    record DateOrder(long violations, long compared, List<Object> examples) {}

    /** Null when the pipeline did not measure this pair of columns as dates. */
    static DateOrder dateOrder(Map<String, Object> crossChecks, String first, String second) {
        for (Object item : listValue(crossChecks.get("date_order"))) {
            Map<String, Object> pair = mapValue(item);
            boolean sameOrder = first.equals(pair.get("first")) && second.equals(pair.get("second"));
            boolean reversed = second.equals(pair.get("first")) && first.equals(pair.get("second"));
            if (!sameOrder && !reversed) {
                continue;
            }
            return new DateOrder(
                longValue(pair.get(sameOrder ? "first_after_second" : "second_after_first")),
                longValue(pair.get("compared")),
                listValue(pair.get(sameOrder ? "first_after_second_examples" : "second_after_first_examples"))
            );
        }
        return null;
    }

    static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> listValue(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }
}
