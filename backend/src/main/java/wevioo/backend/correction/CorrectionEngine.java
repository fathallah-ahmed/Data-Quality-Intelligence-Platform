package wevioo.backend.correction;

import org.springframework.stereotype.Component;
import wevioo.backend.correction.Operations.OpSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Applies a list of corrections to one table, a row at a time, and says what each one changed. The
 * same pass serves the preview (nothing written) and the real run (the corrected file is written).
 */
@Component
public class CorrectionEngine {
    private static final int MAX_EXAMPLES = 5;

    /** One changed value: the data row it is in (the first row after the header is 1), before and after. */
    public record Example(long row, String before, String after) {}

    public static final class OpResult {
        public final OpSpec op;
        public final String description;
        public long affected;
        /** Dates left alone because day and month could be swapped. */
        public long review;
        /** Values the operation could not read (for dates), left alone. */
        public long notRecognised;
        public final List<Example> examples = new ArrayList<>();

        OpResult(OpSpec op) {
            this.op = op;
            this.description = Operations.describe(op);
        }

        void change(long row, String before, String after) {
            affected++;
            if (examples.size() < MAX_EXAMPLES) {
                examples.add(new Example(row, before, after));
            }
        }
    }

    public record RunResult(List<String> columns, long rowsBefore, long rowsAfter, long cellsChanged, long adjustedRows, List<OpResult> operations) {}

    /**
     * @param output where to write the corrected table, or null to only measure (preview)
     * @param ops    operations already checked with {@link Operations#validate}
     */
    public RunResult run(Path source, List<OpSpec> ops, Path output) throws IOException {
        Csv.Dialect dialect = Csv.detect(source);
        try (Csv.RecordReader reader = new Csv.RecordReader(Csv.open(source, dialect), dialect.delimiter());
             Csv.RecordWriter writer = output == null ? null : new Csv.RecordWriter(output)) {
            List<String> header = reader.next();
            if (header == null) {
                throw new IOException("The file is empty");
            }
            header = header.stream().map(String::strip).toList();
            List<CellStep> steps = new ArrayList<>();
            List<OpResult> results = new ArrayList<>();
            for (OpSpec op : ops) {
                OpResult result = new OpResult(op);
                results.add(result);
                steps.add(new CellStep(op, result, op.column() == null ? -1 : header.indexOf(op.column())));
                if (op.column() != null && header.indexOf(op.column()) < 0) {
                    throw new IllegalArgumentException("The table has no column named " + op.column());
                }
            }
            if (writer != null) {
                writer.write(header);
            }

            long rowsBefore = 0;
            long rowsAfter = 0;
            long adjusted = 0;
            Set<String> seen = new HashSet<>();
            List<String> record;
            while ((record = reader.next()) != null) {
                rowsBefore++;
                List<String> row = new ArrayList<>(record);
                if (row.size() != header.size()) {
                    adjusted++;
                    while (row.size() < header.size()) {
                        row.add("");
                    }
                    while (row.size() > header.size()) {
                        row.remove(row.size() - 1);
                    }
                }
                boolean dropped = false;
                for (CellStep step : steps) {
                    if (step.dropsRows()) {
                        if (step.shouldDrop(row, seen, rowsBefore)) {
                            dropped = true;
                            break;
                        }
                    } else {
                        step.apply(row, rowsBefore);
                    }
                }
                if (!dropped) {
                    rowsAfter++;
                    if (writer != null) {
                        writer.write(row);
                    }
                }
            }
            long changed = results.stream().filter(r -> !r.op.type().startsWith("DROP_")).mapToLong(r -> r.affected).sum();
            return new RunResult(header, rowsBefore, rowsAfter, changed, adjusted, results);
        }
    }

    /** One operation ready to run on rows. */
    private static final class CellStep {
        private final OpSpec op;
        private final OpResult result;
        private final int index;
        private final Map<String, String> mapping = new HashMap<>();
        private final boolean ignoreCase;
        private final Set<String> tokens = new HashSet<>();

        CellStep(OpSpec op, OpResult result, int index) {
            this.op = op;
            this.result = result;
            this.index = index;
            this.ignoreCase = Boolean.TRUE.equals(op.settings().get("ignoreCase"));
            if ("REPLACE_VALUES".equals(op.type())) {
                for (Object item : (List<?>) op.settings().get("mappings")) {
                    Map<?, ?> pair = (Map<?, ?>) item;
                    mapping.putIfAbsent(key(String.valueOf(pair.get("from"))), String.valueOf(pair.get("to")));
                }
            }
            if ("EMPTY_TO_NULL".equals(op.type())) {
                for (Object token : (List<?>) op.settings().get("tokens")) {
                    tokens.add(String.valueOf(token).strip().toLowerCase(Locale.ROOT));
                }
            }
        }

        boolean dropsRows() {
            return op.type().startsWith("DROP_");
        }

        private String key(String value) {
            return ignoreCase ? value.toLowerCase(Locale.ROOT) : value;
        }

        boolean shouldDrop(List<String> row, Set<String> seen, long rowNumber) {
            if ("DROP_EMPTY_ROWS".equals(op.type())) {
                String value = row.get(index);
                if (strip(value).isEmpty()) {
                    result.change(rowNumber, "(empty " + op.column() + ")", "(row removed)");
                    return true;
                }
                return false;
            }
            String fingerprint = fingerprint(row);
            if (!seen.add(fingerprint)) {
                result.change(rowNumber, String.join(", ", row.stream().limit(4).toList()), "(row removed)");
                return true;
            }
            return false;
        }

        void apply(List<String> row, long rowNumber) {
            String before = row.get(index);
            String after = transform(before);
            if (!after.equals(before)) {
                row.set(index, after);
                result.change(rowNumber, before, after);
            }
        }

        private String transform(String value) {
            Map<String, Object> s = op.settings();
            switch (op.type()) {
                case "TRIM":
                    return strip(value);
                case "COLLAPSE_SPACES":
                    return value.replaceAll("[ \\t\\u00A0]{2,}", " ");
                case "CASE":
                    return switch (String.valueOf(s.get("mode"))) {
                        case "UPPER" -> value.toUpperCase(Locale.ROOT);
                        case "LOWER" -> value.toLowerCase(Locale.ROOT);
                        default -> titleCase(value);
                    };
                case "REPLACE_VALUES": {
                    String replacement = mapping.get(key(value));
                    return replacement == null ? value : replacement;
                }
                case "REPLACE_TEXT":
                    return value.replace(String.valueOf(s.get("find")), String.valueOf(s.get("with")));
                case "EMPTY_TO_NULL":
                    return tokens.contains(strip(value).toLowerCase(Locale.ROOT)) ? "" : value;
                case "FILL_EMPTY":
                    return strip(value).isEmpty() ? String.valueOf(s.get("value")) : value;
                case "DIGITS_ONLY": {
                    String stripped = strip(value);
                    String digits = stripped.replaceAll("\\D", "");
                    return stripped.startsWith("+") && !digits.isEmpty() ? "+" + digits : digits;
                }
                case "PAD_LEFT": {
                    int width = ((Number) s.get("width")).intValue();
                    String stripped = strip(value);
                    if (stripped.isEmpty() || stripped.length() >= width) {
                        return value;
                    }
                    return String.valueOf(s.get("char")).repeat(width - stripped.length()) + stripped;
                }
                case "NORMALIZE_DATE": {
                    if (strip(value).isEmpty()) {
                        return value;
                    }
                    DateNormalizer.Result date = DateNormalizer.normalize(value, String.valueOf(s.get("order")));
                    switch (date.outcome()) {
                        case AMBIGUOUS -> result.review++;
                        case UNRECOGNISED -> result.notRecognised++;
                        default -> { /* converted or already ISO */ }
                    }
                    return date.value();
                }
                default:
                    return value;
            }
        }
    }

    /** Strip spaces, including the no-break spaces and zero-width marks that web exports leave around values. */
    static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isBlankChar(value.charAt(start))) {
            start++;
        }
        while (end > start && isBlankChar(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isBlankChar(char c) {
        return Character.isWhitespace(c) || c == ' ' || c == ' ' || c == ' ' || c == '﻿' || c == '​';
    }

    static String titleCase(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean startOfWord = true;
        for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
            out.append(startOfWord ? Character.toUpperCase(c) : c);
            startOfWord = c == ' ' || c == '-' || c == '\'' || c == '\t';
        }
        return out.toString();
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
