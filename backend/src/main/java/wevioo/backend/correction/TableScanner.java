package wevioo.backend.correction;

import org.springframework.stereotype.Component;
import wevioo.backend.correction.Operations.OpSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads a table once and reports what could be corrected in it: spaces around values, placeholders such
 * as "N/A", dates written in several ways, one value spelled in several ways, repeated rows. Each finding
 * becomes a suggested operation with the number of values it would touch; nothing is changed here.
 */
@Component
public class TableScanner {
    private static final int MAX_DISTINCT_TRACKED = 5000;
    private static final int MAX_SUGGESTIONS = 25;
    private static final int MAX_EXAMPLES = 3;
    private static final int TOP_VALUES = 12;
    private static final double DATE_COLUMN_SHARE = 0.8;

    public record ValueCount(String value, long count) {}

    public record ColumnSample(String name, long filled, long distinct, List<ValueCount> topValues) {}

    /** A correction found in the data, ready to be previewed or added to the plan. */
    public record Suggestion(String key, OpSpec operation, String description, String rationale, long affectedRows, List<String> examples) {}

    public record Scan(List<String> columns, long rows, List<ColumnSample> samples, List<Suggestion> suggestions) {}

    private static final class ColumnStats {
        long filled;
        long padded;
        long multiSpace;
        long dateLike;
        long dateConvertible;
        long dateAmbiguous;
        final Map<String, Long> placeholders = new TreeMap<>();
        final Map<String, Long> frequencies = new LinkedHashMap<>();
        boolean frequenciesTruncated;
        final List<String> paddedExamples = new ArrayList<>();
        final List<String> placeholderExamples = new ArrayList<>();
        final List<String> dateExamples = new ArrayList<>();
    }

    public Scan scan(Path source) throws IOException {
        Csv.Dialect dialect = Csv.detect(source);
        try (Csv.RecordReader reader = new Csv.RecordReader(Csv.open(source, dialect), dialect.delimiter())) {
            List<String> header = reader.next();
            if (header == null) {
                throw new IOException("The file is empty");
            }
            List<String> columns = header.stream().map(CorrectionEngine::strip).toList();
            List<ColumnStats> stats = new ArrayList<>();
            columns.forEach(c -> stats.add(new ColumnStats()));
            Set<String> placeholderSet = new HashSet<>();
            Operations.EMPTY_TOKENS.forEach(t -> placeholderSet.add(t.toLowerCase(Locale.ROOT)));

            long rows = 0;
            long duplicates = 0;
            Set<String> seen = new HashSet<>();
            List<String> record;
            while ((record = reader.next()) != null) {
                rows++;
                List<String> row = new ArrayList<>(record);
                while (row.size() < columns.size()) {
                    row.add("");
                }
                for (int i = 0; i < columns.size(); i++) {
                    observe(stats.get(i), row.get(i), placeholderSet);
                }
                if (!seen.add(fingerprint(row.subList(0, columns.size())))) {
                    duplicates++;
                }
            }

            List<ColumnSample> samples = new ArrayList<>();
            List<Suggestion> suggestions = new ArrayList<>();
            if (duplicates > 0) {
                suggestions.add(new Suggestion("DROP_DUPLICATE_ROWS", new OpSpec("DROP_DUPLICATE_ROWS", null, Map.of()),
                    Operations.describe(new OpSpec("DROP_DUPLICATE_ROWS", null, Map.of())),
                    duplicates + " of " + rows + " rows are an exact copy of an earlier row.", duplicates, List.of()));
            }
            for (int i = 0; i < columns.size(); i++) {
                ColumnStats column = stats.get(i);
                samples.add(sample(columns.get(i), column));
                suggestions.addAll(suggestFor(columns.get(i), column));
            }
            return new Scan(columns, rows, samples, suggestions.stream().limit(MAX_SUGGESTIONS).toList());
        }
    }

    private void observe(ColumnStats column, String raw, Set<String> placeholders) {
        String value = CorrectionEngine.strip(raw);
        if (value.isEmpty()) {
            return;
        }
        column.filled++;
        if (!value.equals(raw)) {
            column.padded++;
            addExample(column.paddedExamples, "'" + raw + "'");
        }
        if (value.matches(".*[ \\t\\u00A0]{2,}.*")) {
            column.multiSpace++;
        }
        if (placeholders.contains(value.toLowerCase(Locale.ROOT))) {
            column.placeholders.merge(value, 1L, Long::sum);
            addExample(column.placeholderExamples, "'" + value + "'");
        }
        DateNormalizer.Result date = DateNormalizer.normalize(value, "AUTO");
        if (date.outcome() != DateNormalizer.Outcome.UNRECOGNISED) {
            column.dateLike++;
            if (date.outcome() == DateNormalizer.Outcome.CONVERTED) {
                column.dateConvertible++;
                addExample(column.dateExamples, "'" + value + "' → '" + date.value() + "'");
            } else if (date.outcome() == DateNormalizer.Outcome.AMBIGUOUS) {
                column.dateAmbiguous++;
            }
        }
        if (column.frequencies.containsKey(value) || column.frequencies.size() < MAX_DISTINCT_TRACKED) {
            column.frequencies.merge(value, 1L, Long::sum);
        } else {
            column.frequenciesTruncated = true;
        }
    }

    private ColumnSample sample(String name, ColumnStats column) {
        List<ValueCount> top = column.frequencies.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(TOP_VALUES)
            .map(entry -> new ValueCount(entry.getKey(), entry.getValue()))
            .toList();
        return new ColumnSample(name, column.filled, column.frequencies.size(), top);
    }

    private List<Suggestion> suggestFor(String name, ColumnStats column) {
        List<Suggestion> found = new ArrayList<>();
        if (!column.placeholders.isEmpty()) {
            long count = column.placeholders.values().stream().mapToLong(Long::longValue).sum();
            OpSpec op = new OpSpec("EMPTY_TO_NULL", name, Map.of("tokens", new ArrayList<>(column.placeholders.keySet())));
            found.add(suggestion(op, "Placeholders such as " + String.join(", ", column.placeholders.keySet().stream().limit(4).toList())
                + " stand for a missing value (" + count + " cell(s)); empty they are counted as missing.", count, column.placeholderExamples));
        }
        if (column.padded > 0) {
            found.add(suggestion(new OpSpec("TRIM", name, Map.of()),
                column.padded + " value(s) have spaces before or after them, so they differ from the same value without.", column.padded, column.paddedExamples));
        }
        if (column.multiSpace > 0) {
            found.add(suggestion(new OpSpec("COLLAPSE_SPACES", name, Map.of()),
                column.multiSpace + " value(s) contain repeated spaces.", column.multiSpace, List.of()));
        }
        boolean dateColumn = column.filled > 0 && column.dateLike >= DATE_COLUMN_SHARE * column.filled;
        if (dateColumn && (column.dateConvertible > 0 || column.dateAmbiguous > 0)) {
            String review = column.dateAmbiguous > 0
                ? " " + column.dateAmbiguous + " value(s) can be read two ways (day/month): they are left as they are for you to decide."
                : "";
            found.add(suggestion(new OpSpec("NORMALIZE_DATE", name, Map.of("order", "AUTO")),
                column.dateConvertible + " date(s) are not written as yyyy-MM-dd." + review,
                column.dateConvertible, column.dateExamples));
        }
        if (!dateColumn) {
            found.addAll(spellingVariants(name, column));
        }
        return found;
    }

    /** One value spelled several ways ("france", "France", "FRANCE"): map the rare spellings to the most frequent one. */
    private List<Suggestion> spellingVariants(String name, ColumnStats column) {
        if (column.frequenciesTruncated) {
            return List.of();
        }
        Map<String, List<Map.Entry<String, Long>>> groups = new HashMap<>();
        for (Map.Entry<String, Long> entry : column.frequencies.entrySet()) {
            groups.computeIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(entry);
        }
        List<Map<String, String>> mappings = new ArrayList<>();
        long affected = 0;
        List<String> examples = new ArrayList<>();
        for (List<Map.Entry<String, Long>> variants : groups.values()) {
            if (variants.size() < 2) {
                continue;
            }
            variants.sort(Comparator.comparing((Map.Entry<String, Long> e) -> e.getValue()).reversed().thenComparing(Map.Entry::getKey));
            String chosen = variants.get(0).getKey();
            for (Map.Entry<String, Long> variant : variants.subList(1, variants.size())) {
                if (mappings.size() < 30) {
                    mappings.add(Map.of("from", variant.getKey(), "to", chosen));
                    affected += variant.getValue();
                    addExample(examples, "'" + variant.getKey() + "' → '" + chosen + "'");
                }
            }
        }
        if (mappings.isEmpty()) {
            return List.of();
        }
        OpSpec op = new OpSpec("REPLACE_VALUES", name, Map.of("mappings", mappings, "ignoreCase", false));
        return List.of(suggestion(op, mappings.size() + " value(s) are written in several ways that differ only by capital letters; "
            + "they are mapped to the most frequent spelling.", affected, examples));
    }

    private Suggestion suggestion(OpSpec op, String rationale, long affected, List<String> examples) {
        String key = op.type() + (op.column() == null ? "" : ":" + op.column());
        return new Suggestion(key, op, Operations.describe(op), rationale, affected, examples);
    }

    private static void addExample(List<String> examples, String example) {
        if (examples.size() < MAX_EXAMPLES && !examples.contains(example)) {
            examples.add(example);
        }
    }

    private static String fingerprint(List<String> row) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            for (String cell : row) {
                digest.update(cell.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            return new String(digest.digest(), StandardCharsets.ISO_8859_1);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }
}
