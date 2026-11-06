package wevioo.backend.graph;

import org.springframework.stereotype.Component;
import wevioo.backend.correction.Csv;
import wevioo.backend.graph.GraphModel.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides how the rows of a version become a graph: which kinds of node there are, what identifies each,
 * which columns are its properties and how the nodes relate. Every choice rests on the column roles the
 * analysis found and on what the values really do; a model is never asked to invent structure.
 *
 * <p>The central question is what a row stands for. A table of accounts that has a customer_id column is
 * not a table of customers, but the customer exists in it: when a column that repeats (a reference) is
 * followed by other columns that keep the same value whenever it repeats, those columns describe that
 * referenced thing, so they become its properties and the thing becomes a node of its own.
 */
@Component
public class GraphModeler {
    /**
     * Share of repeated key values that must agree on a column for the column to belong to that key. It is not
     * 100% on purpose: in dirty data a customer has two spellings of one email, and that must show up as a
     * conflict on the customer, not hide the fact that the email describes the customer.
     */
    static final double DEPENDENCY_SHARE = 0.75;
    /**
     * How far above chance the agreement must be. A column that is the same on 95% of all rows agrees with
     * itself 90% of the time whatever the key is, which says nothing about the key.
     */
    static final double CHANCE_MARGIN = 0.3;
    private static final int MAX_FREQUENCIES = 1000;
    /** Repeated key values needed before a dependency is believed. */
    static final int MIN_EVIDENCE = 3;
    static final int MAX_DISTINCT_TRACKED = 300_000;
    static final int PROMOTABLE_MAX_DISTINCT = 100;
    private static final int MAX_EXAMPLES = 3;
    private static final Set<String> PROMOTABLE_ROLES = Set.of("LOCATION", "CATEGORY", "STATUS");
    /** Share of a column's values that must be line numbers of a table for the column to point at that table's rows. */
    static final double POSITION_COVERAGE = 0.95;
    /** Below this, a column that only partly fits as line numbers is not even worth mentioning. */
    private static final double POSITION_HINT = 0.3;
    /** How far into the rows the values must reach: a column that pointed at all rows of a file reaches its end. */
    static final double POSITION_REACH = 0.9;
    /** Words that say "this is a file" and not what its rows are: friends_table holds friends. */
    private static final Set<String> FILE_WORDS = Set.of("table", "tbl", "tab", "csv", "file");
    private static final Set<String> KEY_WORDS = Set.of("id", "identifier", "ref", "reference", "key", "code", "uuid", "guid", "no", "num", "number", "pk", "fk");

    /** How well one column follows one repeating key. */
    record Dependency(long considered, long disagreeing, List<String> examples) {
        double share() {
            return considered == 0 ? 0 : 1.0 - (double) disagreeing / considered;
        }
    }

    /** @param chance per column, the chance that two rows picked at random hold the same value */
    record TableScan(Map<String, Map<String, Dependency>> dependencies, long duplicateKeys, long blankKeys, List<String> duplicateExamples,
                     boolean truncated, Map<String, Double> chance) {}

    public Schema propose(List<TableInput> tables, List<Link> links, Set<String> promote) throws IOException {
        List<NodeType> nodes = new ArrayList<>();
        List<RelType> relationships = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        List<Promotable> promotable = new ArrayList<>();
        Set<String> usedLabels = new HashSet<>();

        Map<String, String> rowLabels = new LinkedHashMap<>();
        Map<String, String> keys = new HashMap<>();
        for (TableInput table : tables) {
            rowLabels.put(table.name(), unique(label(table.name(), true), usedLabels));
            keys.put(table.name(), pickKey(table, findings));
        }

        compositeKeys(tables, links, keys, findings);
        Map<String, Map<String, String>> positional = positionalLinks(tables, links, keys, findings);

        for (TableInput table : tables) {
            String rowLabel = rowLabels.get(table.name());
            String keyColumn = keys.get(table.name());
            Map<String, String> byPosition = positional.getOrDefault(table.name(), Map.of());
            List<String> keyParts = keyColumn != null && keyColumn.contains(GraphModel.KEY_JOIN) ? GraphModel.keyParts(keyColumn) : List.of();

            // Foreign keys: columns whose values are the key of another table's rows.
            Map<String, Link> foreign = new LinkedHashMap<>();
            for (Link link : links) {
                if (!link.sourceTable().equals(table.name()) || link.sourceTable().equals(link.targetTable())) {
                    continue;
                }
                if (!rowLabels.containsKey(link.targetTable()) || link.sourceColumn().equals(keyColumn)) {
                    continue;
                }
                if (!link.targetColumn().equals(keys.get(link.targetTable()))) {
                    findings.add(new Finding("warning", "RELATION_NOT_USED", table.name() + "." + link.sourceColumn() + " matches "
                        + link.targetTable() + "." + link.targetColumn() + ", which is not the identifier of " + link.targetTable()
                        + ", so no relationship is drawn from it.", List.of()));
                    continue;
                }
                foreign.putIfAbsent(link.sourceColumn(), link);
            }
            List<Column> unlinked = table.columns().stream()
                .filter(c -> "REFERENCE".equals(c.role()) && !c.name().equals(keyColumn) && !foreign.containsKey(c.name())
                    && !byPosition.containsKey(c.name()) && !keyParts.contains(c.name()))
                .toList();
            // A column that holds the identifiers of the table's own rows (staff.manager_id) points at another row of the same table.
            Set<String> selfReferences = keyColumn != null && keyParts.isEmpty() ? selfReferences(table, keyColumn, unlinked) : Set.of();
            List<Column> references = unlinked.stream().filter(c -> !selfReferences.contains(c.name())).toList();
            List<Column> candidates = table.columns().stream()
                .filter(c -> !c.name().equals(keyColumn) && !foreign.containsKey(c.name()) && !byPosition.containsKey(c.name())
                    && !selfReferences.contains(c.name()) && !keyParts.contains(c.name())
                    && references.stream().noneMatch(r -> r.name().equals(c.name()))
                    && !"REFERENCE".equals(c.role()))
                .toList();

            // A table that only says which two things go together (friends: user, user) is a relationship, not a thing.
            List<String> ends = table.columns().stream()
                .map(Column::name).filter(c -> foreign.containsKey(c) || byPosition.containsKey(c)).toList();
            if (keyColumn == null && ends.size() == 2 && table.columns().size() == 2) {
                String[] labels = new String[2];
                boolean[] positions = new boolean[2];
                for (int i = 0; i < 2; i++) {
                    positions[i] = byPosition.containsKey(ends.get(i));
                    labels[i] = rowLabels.get(positions[i] ? byPosition.get(ends.get(i)) : foreign.get(ends.get(i)).targetTable());
                }
                String type = snake(rowLabel);
                relationships.add(new RelType(type, labels[0], labels[1], table.name(), ends.get(0), ends.get(1),
                    "each row of " + table.name() + " says that the " + labels[0] + " in " + ends.get(0) + " and the " + labels[1] + " in "
                        + ends.get(1) + " are linked; the direction is the order of the two columns",
                    positions[0], positions[1]));
                findings.add(new Finding("info", "LINK_TABLE", table.name() + " only holds two references (" + ends.get(0) + ", " + ends.get(1)
                    + "), so it becomes the relationship " + type + " between " + labels[0] + " and " + labels[1] + " and has no node of its own.", List.of()));
                continue;
            }

            TableScan scan = scan(table, keyColumn, references, candidates);
            addKeyFindings(table, keyColumn, scan, findings);
            if (scan.truncated()) {
                findings.add(new Finding("warning", "TRUNCATED", table.name() + " has more than " + MAX_DISTINCT_TRACKED
                    + " distinct values in a referenced column; the dependencies between its columns were measured on the first ones only.", List.of()));
            }

            // Which referenced thing does each column describe?
            Map<String, List<Property>> derivedProperties = new LinkedHashMap<>();
            references.forEach(r -> derivedProperties.put(r.name(), new ArrayList<>()));
            List<Property> rowProperties = new ArrayList<>();
            // The parts of a composite key that point at nothing (a line number inside an order) are still worth keeping.
            for (Column column : table.columns()) {
                if (keyParts.contains(column.name()) && !foreign.containsKey(column.name()) && !byPosition.containsKey(column.name())) {
                    rowProperties.add(new Property(column.name(), column.role()));
                }
            }
            for (Column column : candidates) {
                Column owner = ownerOf(column, references, scan);
                Property property = new Property(column.name(), column.role());
                if (owner == null) {
                    rowProperties.add(property);
                } else {
                    derivedProperties.get(owner.name()).add(property);
                    Dependency dependency = scan.dependencies().get(owner.name()).get(column.name());
                    if (dependency.disagreeing() > 0) {
                        findings.add(new Finding("conflict", "DEPENDENCY",
                            owner.name() + " → " + column.name() + ": " + dependency.disagreeing() + " of " + dependency.considered()
                                + " repeated value(s) of " + owner.name() + " come with different values of " + column.name()
                                + ". The graph keeps every value as a candidate and chooses none.",
                            dependency.examples()));
                    }
                }
            }

            nodes.add(new NodeType(rowLabel, table.name(), keyColumn, false,
                keyColumn == null ? "Each row of " + table.name() + " is one " + rowLabel + " (it has no identifier)"
                    : "One row of " + table.name() + " is one " + rowLabel + ", identified by " + keyColumn,
                null, rowProperties));
            for (String column : selfReferences) {
                relationships.add(new RelType(snake(label(column, false)), rowLabel, rowLabel, table.name(), keyColumn, column,
                    "the values of " + table.name() + "." + column + " are identifiers of other rows of " + table.name() + ", so a " + rowLabel
                        + " points at the " + rowLabel + " it names"));
                findings.add(new Finding("info", "SELF_REFERENCE", table.name() + "." + column + " holds identifiers of " + table.name()
                    + " itself, so it becomes a relationship between two " + rowLabel + " nodes, not a node of its own.", List.of()));
            }
            for (Column reference : references) {
                String derivedLabel = unique(label(reference.name(), false), usedLabels);
                nodes.add(new NodeType(derivedLabel, table.name(), reference.name(), true,
                    reference.name() + " repeats across rows of " + table.name() + ", so it names a " + derivedLabel + " that several rows share",
                    null, derivedProperties.get(reference.name())));
                relationships.add(new RelType("HAS_" + snake(rowLabel), derivedLabel, rowLabel, table.name(), reference.name(), keyColumn,
                    "each row of " + table.name() + " belongs to the " + derivedLabel + " named by " + reference.name()));
            }
            for (Map.Entry<String, String> position : byPosition.entrySet()) {
                relationships.add(new RelType("HAS_" + snake(rowLabel), rowLabels.get(position.getValue()), rowLabel, table.name(),
                    position.getKey(), keyColumn,
                    "the values of " + table.name() + "." + position.getKey() + " are line numbers of " + position.getValue()
                        + " (it has no identifier, so a row is named by its position in the file)",
                    true, false));
            }
            for (Link link : foreign.values()) {
                relationships.add(new RelType("HAS_" + snake(rowLabel), rowLabels.get(link.targetTable()), rowLabel, table.name(),
                    link.sourceColumn(), keyColumn,
                    "the values of " + table.name() + "." + link.sourceColumn() + " match " + link.targetTable() + "." + link.targetColumn()
                        + " (found by the analysis)"));
            }

            promoteColumns(table, rowLabel, keyColumn, nodes, relationships, promotable, promote, usedLabels);
        }
        // Safety net: a relationship must never start or end at a node that does not exist.
        Set<String> existing = new HashSet<>();
        nodes.forEach(n -> existing.add(n.label()));
        relationships.removeIf(r -> {
            boolean dangling = !existing.contains(r.fromLabel()) || !existing.contains(r.toLabel());
            if (dangling) {
                findings.add(new Finding("warning", "RELATION_NOT_USED", r.table() + "." + r.fromColumn() + " pointed at a table that has no node in this graph, so no relationship is drawn from it.", List.of()));
            }
            return dangling;
        });
        addConnectivityFindings(tables, nodes, relationships, findings);
        return new Schema(nodes, relationships, findings, promotable, Map.of());
    }

    // ------------------------------------------------------------------ keys made of several columns

    private static final int MAX_COMPOSITE_COLUMNS = 4;
    private static final int MAX_COMPOSITE_ROWS = 1_000_000;

    /**
     * The value that identifies a row: the column's value, or the values of the columns of a composite key joined
     * with "|". Empty when a part is missing, because such a row has no identity.
     */
    static String keyValue(List<String> record, Map<String, Integer> index, String keyColumn) {
        Integer position = index.get(keyColumn);
        if (position != null) {
            return cell(record, position);
        }
        List<String> parts = GraphModel.keyParts(keyColumn);
        if (parts.size() < 2) {
            return "";
        }
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            Integer at = index.get(part);
            String value = at == null ? "" : cell(record, at);
            if (value.isEmpty()) {
                return "";
            }
            key.append(key.length() == 0 ? "" : "|").append(value);
        }
        return key.toString();
    }

    /**
     * A table with no identifier column whose rows are still told apart by the references they hold (order_id with
     * a line number, store_id with product_id) is identified by the smallest such combination that never repeats.
     * A table that holds nothing but its references is a relationship and keeps no key.
     */
    private void compositeKeys(List<TableInput> tables, List<Link> links, Map<String, String> keys, List<Finding> findings) throws IOException {
        Set<String> names = new HashSet<>();
        tables.forEach(t -> names.add(t.name()));
        for (TableInput table : tables) {
            if (keys.get(table.name()) != null) {
                continue;
            }
            List<String> candidates = table.columns().stream()
                .filter(c -> "REFERENCE".equals(c.role()) || links.stream().anyMatch(l -> l.sourceTable().equals(table.name())
                    && l.sourceColumn().equals(c.name()) && !l.targetTable().equals(table.name()) && names.contains(l.targetTable())
                    && l.targetColumn().equals(keys.get(l.targetTable()))))
                .map(Column::name).limit(MAX_COMPOSITE_COLUMNS).toList();
            if (candidates.size() < 2 || table.columns().size() <= candidates.size()) {
                continue;
            }
            List<List<String>> subsets = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                for (int j = i + 1; j < candidates.size(); j++) {
                    subsets.add(List.of(candidates.get(i), candidates.get(j)));
                }
            }
            for (int i = 0; i < candidates.size(); i++) {
                for (int j = i + 1; j < candidates.size(); j++) {
                    for (int k = j + 1; k < candidates.size(); k++) {
                        subsets.add(List.of(candidates.get(i), candidates.get(j), candidates.get(k)));
                    }
                }
            }
            List<String> unique = uniqueCombination(table, subsets);
            if (unique != null) {
                String key = String.join(GraphModel.KEY_JOIN, unique);
                keys.put(table.name(), key);
                // The warning about a table with no identifier was raised before this key was found.
                findings.removeIf(f -> f.kind().equals("NO_KEY") && f.message().startsWith("Nothing identifies the rows of " + table.name() + ":"));
                findings.add(new Finding("info", "COMPOSITE_KEY", "No single column identifies the rows of " + table.name() + ", but "
                    + String.join(" and ", unique) + " together never repeat, so together they identify a row.", List.of()));
            }
        }
    }

    /** The first combination (they come smallest first) that is filled and different on every row, or null. */
    private static List<String> uniqueCombination(TableInput table, List<List<String>> subsets) throws IOException {
        long rows = countRows(table);
        if (rows < 2 || rows > MAX_COMPOSITE_ROWS) {
            return null;
        }
        long[][] hashes = new long[subsets.size()][(int) rows];
        boolean[] blank = new boolean[subsets.size()];
        int count = 0;
        Csv.Dialect dialect = Csv.detect(table.file());
        try (Csv.RecordReader reader = new Csv.RecordReader(Csv.open(table.file(), dialect), dialect.delimiter())) {
            List<String> header = reader.next();
            if (header == null) {
                return null;
            }
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                index.putIfAbsent(header.get(i).strip(), i);
            }
            List<String> record;
            while ((record = reader.next()) != null && count < rows) {
                for (int s = 0; s < subsets.size(); s++) {
                    String key = keyValue(record, index, String.join(GraphModel.KEY_JOIN, subsets.get(s)));
                    if (key.isEmpty()) {
                        blank[s] = true;
                    } else {
                        hashes[s][count] = fnv(key);
                    }
                }
                count++;
            }
        }
        for (int s = 0; s < subsets.size(); s++) {
            if (blank[s]) {
                continue;
            }
            long[] sorted = java.util.Arrays.copyOf(hashes[s], count);
            java.util.Arrays.sort(sorted);
            boolean repeats = false;
            for (int i = 1; i < sorted.length && !repeats; i++) {
                repeats = sorted[i] == sorted[i - 1];
            }
            if (!repeats) {
                return subsets.get(s);
            }
        }
        return null;
    }

    private static long fnv(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash = (hash ^ text.charAt(i)) * 0x100000001b3L;
        }
        return hash;
    }

    // ------------------------------------------------------------------ rows that point at other rows of the same table

    /**
     * Columns whose values are, nearly all, identifiers of the same table: a manager_id in a table of staff. A column
     * that merely repeats the identifier on every row is a copy of it, not a link, and is left alone.
     */
    private Set<String> selfReferences(TableInput table, String keyColumn, List<Column> columns) throws IOException {
        if (columns.isEmpty()) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        Map<String, Set<String>> values = new LinkedHashMap<>();
        columns.forEach(c -> values.put(c.name(), new HashSet<>()));
        Csv.Dialect dialect = Csv.detect(table.file());
        try (Csv.RecordReader reader = new Csv.RecordReader(Csv.open(table.file(), dialect), dialect.delimiter())) {
            List<String> header = reader.next();
            if (header == null) {
                return Set.of();
            }
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                index.putIfAbsent(header.get(i).strip(), i);
            }
            List<String> record;
            while ((record = reader.next()) != null) {
                String key = keyValue(record, index, keyColumn);
                if (!key.isEmpty() && keys.size() < MAX_DISTINCT_TRACKED) {
                    keys.add(key);
                }
                for (Column column : columns) {
                    Integer at = index.get(column.name());
                    String value = at == null ? "" : cell(record, at);
                    Set<String> seen = values.get(column.name());
                    if (!value.isEmpty() && seen.size() < MAX_DISTINCT_TRACKED) {
                        seen.add(value);
                    }
                }
            }
        }
        Set<String> found = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : values.entrySet()) {
            Set<String> seen = entry.getValue();
            if (seen.isEmpty() || seen.equals(keys)) {
                continue;
            }
            long inside = seen.stream().filter(keys::contains).count();
            if ((double) inside / seen.size() >= POSITION_COVERAGE) {
                found.add(entry.getKey());
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ tables that are tied to nothing

    private void addConnectivityFindings(List<TableInput> tables, List<NodeType> nodes, List<RelType> relationships, List<Finding> findings) {
        if (tables.size() < 2) {
            return;
        }
        Map<String, String> tableOf = new HashMap<>();
        nodes.forEach(n -> tableOf.put(n.label(), n.table()));
        Set<String> connected = new HashSet<>();
        for (RelType relationship : relationships) {
            Set<String> ends = new HashSet<>();
            ends.add(relationship.table());
            ends.add(tableOf.getOrDefault(relationship.fromLabel(), relationship.table()));
            ends.add(tableOf.getOrDefault(relationship.toLabel(), relationship.table()));
            if (ends.size() > 1) {
                connected.addAll(ends);
            }
        }
        for (TableInput table : tables) {
            if (!connected.contains(table.name())) {
                findings.add(new Finding("warning", "DISCONNECTED", table.name() + " is not tied to any other table in this graph: no column of it "
                    + "could be matched with the identifier of another table. Its nodes will exist but cannot be reached from the others.", List.of()));
            }
        }
    }

    // ------------------------------------------------------------------ rows named by their position

    /**
     * Some files have no identifier column and are still referred to by number: a table of users in the order of
     * their ids, and friendships that say "1 and 555". A column of another table points at those rows when almost
     * all its values are whole numbers between 1 and the number of rows, and they reach well into that range
     * (so a small code is not mistaken for a row number). When two tables fit equally, or when only some of the
     * values fit, nothing is linked and a finding says why.
     *
     * @return per table, the columns that name rows of a key-less table, with that table
     */
    private Map<String, Map<String, String>> positionalLinks(List<TableInput> tables, List<Link> links, Map<String, String> keys, List<Finding> findings) throws IOException {
        Map<String, Long> targets = new LinkedHashMap<>();
        for (TableInput table : tables) {
            // A table of two references becomes a relationship and has no rows to point at.
            boolean linkTable = table.columns().size() == 2 && table.columns().stream().allMatch(c -> "REFERENCE".equals(c.role()));
            if (keys.get(table.name()) == null && !linkTable) {
                long count = countRows(table);
                if (count >= 2) {
                    targets.put(table.name(), count);
                }
            }
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        if (targets.isEmpty()) {
            return result;
        }
        List<String> targetNames = new ArrayList<>(targets.keySet());
        for (TableInput table : tables) {
            List<Column> columns = table.columns().stream()
                .filter(c -> "REFERENCE".equals(c.role()) && !c.name().equals(keys.get(table.name()))
                    && links.stream().noneMatch(l -> l.sourceTable().equals(table.name()) && l.sourceColumn().equals(c.name())))
                .toList();
            if (columns.isEmpty()) {
                continue;
            }
            long[] filled = new long[columns.size()];
            long[][] largest = new long[columns.size()][targetNames.size()];
            long[][] fitting = new long[columns.size()][targetNames.size()];
            Csv.Dialect dialect = Csv.detect(table.file());
            try (Csv.RecordReader reader = new Csv.RecordReader(Csv.open(table.file(), dialect), dialect.delimiter())) {
                List<String> header = reader.next();
                if (header == null) {
                    continue;
                }
                Map<String, Integer> index = new HashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    index.putIfAbsent(header.get(i).strip(), i);
                }
                int[] at = columns.stream().mapToInt(c -> index.getOrDefault(c.name(), -1)).toArray();
                List<String> record;
                while ((record = reader.next()) != null) {
                    for (int j = 0; j < at.length; j++) {
                        String value = at[j] < 0 ? "" : cell(record, at[j]);
                        if (value.isEmpty()) {
                            continue;
                        }
                        filled[j]++;
                        long number = wholeNumber(value);
                        if (number < 1) {
                            continue;
                        }
                        for (int t = 0; t < targetNames.size(); t++) {
                            if (number <= targets.get(targetNames.get(t))) {
                                fitting[j][t]++;
                                largest[j][t] = Math.max(largest[j][t], number);
                            }
                        }
                    }
                }
            }
            for (int j = 0; j < columns.size(); j++) {
                if (filled[j] == 0) {
                    continue;
                }
                List<String> fits = new ArrayList<>();
                double best = 0;
                String bestTable = null;
                for (int t = 0; t < targetNames.size(); t++) {
                    String target = targetNames.get(t);
                    if (target.equals(table.name())) {
                        continue;
                    }
                    double coverage = (double) fitting[j][t] / filled[j];
                    // Only a partial fit is worth a remark: a column that fits everywhere without reaching the end of the rows is a code.
                    if (coverage < POSITION_COVERAGE && coverage > best && largest[j][t] >= POSITION_REACH * targets.get(target)) {
                        best = coverage;
                        bestTable = target;
                    }
                    // The values must also reach into the range, so that a small code is not taken for a line number.
                    if (coverage >= POSITION_COVERAGE && largest[j][t] >= POSITION_REACH * targets.get(target)) {
                        fits.add(target);
                    }
                }
                String name = table.name() + "." + columns.get(j).name();
                if (fits.size() == 1) {
                    result.computeIfAbsent(table.name(), k -> new LinkedHashMap<>()).put(columns.get(j).name(), fits.get(0));
                } else if (fits.size() > 1) {
                    findings.add(new Finding("warning", "AMBIGUOUS_LINK", name + " could be the line number of a row of " + String.join(" or ", fits)
                        + " (its values fit both), so it is not linked to either.", List.of()));
                } else if (bestTable != null && best >= POSITION_HINT) {
                    findings.add(new Finding("warning", "PARTIAL_LINK", name + ": " + Math.round(best * 100) + "% of its values are line numbers of "
                        + bestTable + " (1 to " + targets.get(bestTable) + "), the others are not. That is too few to say it points at those rows, "
                        + "so it is not linked. If it should be, its values need to be checked.", List.of()));
                }
            }
        }
        return result;
    }

    private static long countRows(TableInput table) throws IOException {
        Csv.Dialect dialect = Csv.detect(table.file());
        long rows = 0;
        try (Csv.RecordReader reader = new Csv.RecordReader(Csv.open(table.file(), dialect), dialect.delimiter())) {
            if (reader.next() == null) {
                return 0;
            }
            while (reader.next() != null) {
                rows++;
            }
        }
        return rows;
    }

    /** The value as a whole number (7 and 7.0 are both 7), or 0 when it is not one. */
    static long wholeNumber(String value) {
        String digits = value.endsWith(".0") ? value.substring(0, value.length() - 2) : value;
        if (digits.isEmpty() || digits.length() > 15) {
            return 0;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                return 0;
            }
        }
        return Long.parseLong(digits);
    }

    // ------------------------------------------------------------------ keys

    private String pickKey(TableInput table, List<Finding> findings) {
        Column key = table.columns().stream()
            .filter(c -> "IDENTIFIER".equals(c.role()))
            .max(Comparator.comparingDouble(Column::confidence).thenComparingLong(Column::distinct))
            .orElse(null);
        if (key == null) {
            key = table.columns().stream()
                .filter(c -> !"REFERENCE".equals(c.role()) && c.missing() == 0 && table.rows() > 1 && c.distinct() == table.rows())
                .findFirst().orElse(null);
            if (key != null) {
                findings.add(new Finding("info", "KEY_BY_VALUES", "No column of " + table.name() + " was recognised as an identifier; "
                    + key.name() + " is filled and different on every row, so it identifies the rows.", List.of()));
            }
        }
        if (key == null) {
            findings.add(new Finding("warning", "NO_KEY", "Nothing identifies the rows of " + table.name()
                + ": each row becomes its own node, and rows cannot be matched with other tables.", List.of()));
            return null;
        }
        return key.name();
    }

    private void addKeyFindings(TableInput table, String keyColumn, TableScan scan, List<Finding> findings) {
        if (keyColumn == null) {
            return;
        }
        if (scan.blankKeys() > 0) {
            findings.add(new Finding("warning", "BLANK_KEY", scan.blankKeys() + " row(s) of " + table.name() + " have no " + keyColumn
                + " and are left out of the graph.", List.of()));
        }
        if (scan.duplicateKeys() > 0) {
            findings.add(new Finding("conflict", "KEY_DUPLICATE", keyColumn + " is not unique in " + table.name() + ": " + scan.duplicateKeys()
                + " row(s) repeat a value already seen. Rows with the same " + keyColumn + " become one node; a property that differs between them is kept as candidates.",
                scan.duplicateExamples()));
        }
    }

    // ------------------------------------------------------------------ what each column describes

    /** The repeating key that determines the column, preferring the one with the fewest distinct values. */
    private Column ownerOf(Column column, List<Column> references, TableScan scan) {
        if (column.distinct() <= 1) {
            return null;
        }
        Column best = null;
        for (Column reference : references) {
            Dependency dependency = scan.dependencies().getOrDefault(reference.name(), Map.of()).get(column.name());
            if (dependency == null || dependency.considered() < MIN_EVIDENCE || dependency.share() < DEPENDENCY_SHARE) {
                continue;
            }
            if (dependency.share() - scan.chance().getOrDefault(column.name(), 0.0) < CHANCE_MARGIN) {
                continue;
            }
            if (best == null || reference.distinct() < best.distinct()) {
                best = reference;
            }
        }
        return best;
    }

    private void promoteColumns(TableInput table, String rowLabel, String keyColumn, List<NodeType> nodes, List<RelType> relationships,
                                List<Promotable> promotable, Set<String> promote, Set<String> usedLabels) {
        for (Column column : table.columns()) {
            if (!PROMOTABLE_ROLES.contains(column.role()) || column.distinct() < 2 || column.distinct() > PROMOTABLE_MAX_DISTINCT) {
                continue;
            }
            NodeType owner = nodes.stream().filter(n -> n.table().equals(table.name())
                && n.properties().stream().anyMatch(p -> p.column().equals(column.name()))).findFirst().orElse(null);
            if (owner == null) {
                continue;
            }
            promotable.add(new Promotable(table.name(), column.name(), column.distinct()));
            if (!promote.contains(table.name() + "." + column.name()) && !promote.contains(column.name())) {
                continue;
            }
            String label = unique(label(column.name(), false), usedLabels);
            List<Property> kept = owner.properties().stream().filter(p -> !p.column().equals(column.name())).toList();
            nodes.set(nodes.indexOf(owner), new NodeType(owner.label(), owner.table(), owner.keyColumn(), owner.derived(), owner.origin(), owner.description(), kept));
            nodes.add(new NodeType(label, table.name(), column.name(), true,
                "The values of " + column.name() + " (" + column.distinct() + " different) become nodes of their own",
                null, List.of()));
            relationships.add(new RelType("HAS_" + snake(label), owner.label(), label, table.name(), owner.keyColumn(), column.name(),
                "each " + owner.label() + " points to the " + label + " in " + column.name()));
        }
    }

    // ------------------------------------------------------------------ reading the data

    private TableScan scan(TableInput table, String keyColumn, List<Column> references, List<Column> candidates) throws IOException {
        Csv.Dialect dialect = Csv.detect(table.file());
        Map<String, Map<String, Dependency>> result = new LinkedHashMap<>();
        Map<String, Map<String, Agg>> aggregates = new LinkedHashMap<>();
        references.forEach(r -> aggregates.put(r.name(), new HashMap<>()));
        Set<String> seenKeys = new HashSet<>();
        List<String> duplicateExamples = new ArrayList<>();
        long duplicateKeys = 0;
        long blankKeys = 0;
        boolean truncated = false;
        List<Map<String, Long>> frequencies = new ArrayList<>();
        boolean[] manyValues = new boolean[candidates.size()];
        long[] filled = new long[candidates.size()];
        candidates.forEach(c -> frequencies.add(new HashMap<>()));

        try (Csv.RecordReader reader = new Csv.RecordReader(Csv.open(table.file(), dialect), dialect.delimiter())) {
            List<String> header = reader.next();
            if (header == null) {
                return new TableScan(result, 0, 0, List.of(), false, Map.of());
            }
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                index.putIfAbsent(header.get(i).strip(), i);
            }
            int[] candidateIndex = candidates.stream().mapToInt(c -> index.getOrDefault(c.name(), -1)).toArray();
            List<String> record;
            while ((record = reader.next()) != null) {
                for (int j = 0; j < candidateIndex.length; j++) {
                    String value = candidateIndex[j] < 0 ? "" : cell(record, candidateIndex[j]);
                    if (!value.isEmpty()) {
                        filled[j]++;
                        Map<String, Long> counts = frequencies.get(j);
                        if (counts.containsKey(value) || counts.size() < MAX_FREQUENCIES) {
                            counts.merge(value, 1L, Long::sum);
                        } else {
                            manyValues[j] = true;
                        }
                    }
                }
                if (keyColumn != null) {
                    String key = keyValue(record, index, keyColumn);
                    if (key.isEmpty()) {
                        blankKeys++;
                    } else if (!seenKeys.add(key)) {
                        duplicateKeys++;
                        if (duplicateExamples.size() < MAX_EXAMPLES && !duplicateExamples.contains(key)) {
                            duplicateExamples.add(key);
                        }
                    }
                }
                for (Column reference : references) {
                    int referenceIndex = index.getOrDefault(reference.name(), -1);
                    String value = referenceIndex < 0 ? "" : cell(record, referenceIndex);
                    if (value.isEmpty()) {
                        continue;
                    }
                    Map<String, Agg> byValue = aggregates.get(reference.name());
                    Agg agg = byValue.get(value);
                    if (agg == null) {
                        if (byValue.size() >= MAX_DISTINCT_TRACKED) {
                            truncated = true;
                            continue;
                        }
                        agg = new Agg(candidates.size());
                        byValue.put(value, agg);
                    }
                    for (int j = 0; j < candidateIndex.length; j++) {
                        String candidate = candidateIndex[j] < 0 ? "" : cell(record, candidateIndex[j]);
                        agg.observe(j, candidate);
                    }
                }
            }
        }
        for (Column reference : references) {
            Map<String, Dependency> perColumn = new LinkedHashMap<>();
            for (int j = 0; j < candidates.size(); j++) {
                long considered = 0;
                long disagreeing = 0;
                List<String> examples = new ArrayList<>();
                for (Map.Entry<String, Agg> entry : aggregates.get(reference.name()).entrySet()) {
                    Agg agg = entry.getValue();
                    if (agg.counts[j] < 2) {
                        continue;
                    }
                    considered++;
                    if (agg.second[j] != null) {
                        disagreeing++;
                        if (examples.size() < MAX_EXAMPLES) {
                            examples.add(entry.getKey() + ": " + agg.first[j] + " | " + agg.second[j]);
                        }
                    }
                }
                perColumn.put(candidates.get(j).name(), new Dependency(considered, disagreeing, examples));
            }
            result.put(reference.name(), perColumn);
        }
        // A column with more than 1000 values is far from constant: its chance of agreeing by luck is negligible.
        Map<String, Double> chance = new HashMap<>();
        for (int j = 0; j < candidates.size(); j++) {
            double sum = 0;
            if (!manyValues[j] && filled[j] > 0) {
                for (long count : frequencies.get(j).values()) {
                    double share = (double) count / filled[j];
                    sum += share * share;
                }
            }
            chance.put(candidates.get(j).name(), sum);
        }
        return new TableScan(result, duplicateKeys, blankKeys, duplicateExamples, truncated, chance);
    }

    /** What the rows that share one key value say about each candidate column. */
    private static final class Agg {
        final String[] first;
        final String[] second;
        final int[] counts;

        Agg(int columns) {
            first = new String[columns];
            second = new String[columns];
            counts = new int[columns];
        }

        void observe(int column, String value) {
            if (value.isEmpty()) {
                return;
            }
            counts[column]++;
            if (first[column] == null) {
                first[column] = value;
            } else if (second[column] == null && !first[column].equals(value)) {
                second[column] = value;
            }
        }
    }

    /**
     * The strings the analysis (pandas) reads as "no value". The graph must agree with it: a manager_id of NULL is
     * nobody, not a manager called NULL.
     */
    private static final Set<String> MISSING = Set.of("#N/A", "#N/A N/A", "#NA", "-1.#IND", "-1.#QNAN", "-NaN", "-nan", "1.#IND", "1.#QNAN",
        "<NA>", "N/A", "NA", "NULL", "NaN", "None", "n/a", "nan", "null");

    static String cell(List<String> record, int index) {
        if (index >= record.size()) {
            return "";
        }
        String value = record.get(index).strip();
        return MISSING.contains(value) ? "" : value;
    }

    // ------------------------------------------------------------------ names

    /** A node label from a table or column name: "customer_id" is Customer, "accounts" is Account. */
    static String label(String name, boolean table) {
        List<String> tokens = tokens(name);
        List<String> kept = tokens.stream().filter(t -> table ? !FILE_WORDS.contains(t) : !KEY_WORDS.contains(t)).toList();
        if (kept.isEmpty()) {
            kept = tokens;
        }
        StringBuilder out = new StringBuilder();
        for (String token : kept) {
            String word = singular(token);
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        String label = out.toString();
        if (label.isEmpty()) {
            return "Item";
        }
        label = label.length() > 40 ? label.substring(0, 40) : label;
        return Character.isLetter(label.charAt(0)) ? label : "N" + label;
    }

    static String snake(String label) {
        return label.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static List<String> tokens(String name) {
        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        List<String> tokens = new ArrayList<>();
        for (String token : spaced.split("[^A-Za-z0-9]+")) {
            if (!token.isEmpty()) {
                tokens.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    private static final Map<String, String> IRREGULAR = Map.of("people", "person", "children", "child", "men", "man", "women", "woman");

    private static String singular(String word) {
        if (IRREGULAR.containsKey(word)) {
            return IRREGULAR.get(word);
        }
        if (word.length() > 4 && word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.length() > 4 && word.endsWith("ses")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static String unique(String label, Set<String> used) {
        String candidate = label;
        int number = 2;
        while (!used.add(candidate)) {
            candidate = label + number++;
        }
        return candidate;
    }
}
