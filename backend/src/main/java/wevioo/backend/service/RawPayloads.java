package wevioo.backend.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps raw pipeline JSON small enough to send to the browser. */
final class RawPayloads {
    /** Sweetviz stores the full frequency table of every column; the UI shows the top values only. */
    static final int MAX_DETAIL_VALUES = 20;

    private RawPayloads() {}

    static Map<String, Object> trimmed(Map<String, Object> metrics) {
        if (!(metrics.get("features") instanceof Map<?, ?> features)) {
            return metrics;
        }
        Map<String, Object> trimmedFeatures = new LinkedHashMap<>();
        features.forEach((name, feature) -> trimmedFeatures.put(String.valueOf(name), trimFeature(feature)));
        Map<String, Object> copy = new LinkedHashMap<>(metrics);
        copy.put("features", trimmedFeatures);
        return copy;
    }

    private static Object trimFeature(Object feature) {
        if (!(feature instanceof Map<?, ?> featureMap) || !(featureMap.get("detail") instanceof Map<?, ?> detail)) {
            return feature;
        }
        Map<String, Object> trimmedDetail = new LinkedHashMap<>();
        detail.forEach((key, value) -> trimmedDetail.put(
            String.valueOf(key),
            value instanceof List<?> list && list.size() > MAX_DETAIL_VALUES ? list.subList(0, MAX_DETAIL_VALUES) : value
        ));
        Map<String, Object> copy = new LinkedHashMap<>();
        featureMap.forEach((key, value) -> copy.put(String.valueOf(key), value));
        copy.put("detail", trimmedDetail);
        return copy;
    }
}
