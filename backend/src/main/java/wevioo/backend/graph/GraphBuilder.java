package wevioo.backend.graph;

import org.springframework.stereotype.Component;
import wevioo.backend.correction.Csv;
import wevioo.backend.graph.GraphModel.*;
import wevioo.backend.graph.GraphStore.NodeRow;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the graph of a version from the rows of its tables, following the schema. A node is created once
 * per key, however many rows mention it. When the rows that share a key disagree about a property the
 * builder does not pick one: it keeps every value as a candidate, marks the node as in conflict, and
 * reports it. References to something that does not exist are reported, not silently dropped.
 */
@Component
public class GraphBuilder {
    static final int MAX_NODES = 500_000;
    private static final int MAX_CANDIDATES = 5;
    private static final int MAX_EXAMPLES = 3;

    private final GraphStore store;

    public GraphBuilder(GraphStore store) {
        this.store = store;
    }

    /** What the rows that share a key say about each property of one node. */
    private static final class NodeAgg {
        long rows;
        final Map<String, Set<String>> values = new LinkedHashMap<>();

        void observe(String column, String value) {
            if (value.isEmpty()) {
                return;
            }
            Set<String> seen = values.computeIfAbsent(column, k -> new LinkedHashSet<>());
            if (seen.size() < MAX_CANDIDATES) {
                seen.add(value);
            }
        }
    }

    public BuildResult build(String scope, Schema schema, List<TableInput> tables) throws IOException {
        long started = System.nanoTime();
        // A build replaces the previous one of the same version: nothing from it is left behind.
        store.clear(scope);
        Map<String, Map<String, NodeAgg>> nodes = new LinkedHashMap<>();
        Map<RelType, Set<String>> pairKeys = new LinkedHashMap<>();
        Map<RelType, List<String[]>> pairs = new LinkedHashMap<>();
        Map<NodeType, Long> blankKeys = new LinkedHashMap<>();
        schema.nodes().forEach(n -> nodes.putIfAbsent(n.label(), new LinkedHashMap<>()));
        schema.relationships().forEach(r -> {
            pairKeys.put(r, new LinkedHashSet<>());
            pairs.put(r, new ArrayList<>());
        });

        long totalNodes = 0;
        for (TableInput table : tables) {
            List<NodeType> tableNodes = schema.nodes().stream().filter(n -> n.table().equals(table.name())).toList();
            List<RelType> tableRels = schema.relationships().stream().filter(r -> r.table().equals(table.name())).toList();
            if (tableNodes.isEmpty() && tableRels.isEmpty()) {
                continue;
            }
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
                long rowNumber = 0;
                List<String> record;
                while ((record = reader.next()) != null) {
                    rowNumber++;
                    for (NodeType node : tableNodes) {
                        String key = keyOf(record, index, node.keyColumn(), rowNumber);
                        if (key.isEmpty()) {
                            blankKeys.merge(node, 1L, Long::sum);
                            continue;
                        }
                        Map<String, NodeAgg> byKey = nodes.get(node.label());
                        NodeAgg agg = byKey.get(key);
                        if (agg == null) {
                            if (++totalNodes > MAX_NODES) {
                                throw new IllegalArgumentException("The graph would have more than " + MAX_NODES
                                    + " nodes, which is more than this build handles.");
                            }
                            agg = new NodeAgg();
                            byKey.put(key, agg);
                        }
                        agg.rows++;
                        for (Property property : node.properties()) {
                            agg.observe(property.column(), GraphModeler.cell(record, index.getOrDefault(property.column(), Integer.MAX_VALUE)));
                        }
                    }
                    for (RelType relationship : tableRels) {
                        String from = endOf(record, index, relationship.fromColumn(), relationship.fromByPosition(), rowNumber);
                        String to = endOf(record, index, relationship.toColumn(), relationship.toByPosition(), rowNumber);
                        if (!from.isEmpty() && !to.isEmpty() && pairKeys.get(relationship).add(from + '\u0001' + to)) {
                            pairs.get(relationship).add(new String[] {from, to});
                        }
                    }
                }
            }
        }

        List<Finding> findings = new ArrayList<>(schema.findings());
        blankKeys.forEach((node, count) -> findings.add(new Finding("warning", "BLANK_KEY",
            count + " row(s) of " + node.table() + " have no " + node.keyColumn() + " and are not in the graph (" + node.label() + ").", List.of())));

        // Nodes: write them, and report every property the rows disagree about.
        Map<String, Long> nodeCounts = new LinkedHashMap<>();
        for (NodeType node : schema.nodes()) {
            Map<String, NodeAgg> byKey = nodes.get(node.label());
            Map<String, String> roles = new HashMap<>();
            node.properties().forEach(p -> roles.put(p.column(), p.role()));
            Map<String, List<String>> conflictExamples = new LinkedHashMap<>();
            Map<String, Long> conflictCounts = new LinkedHashMap<>();
            List<NodeRow> rows = new ArrayList<>();
            for (Map.Entry<String, NodeAgg> entry : byKey.entrySet()) {
                rows.add(new NodeRow(entry.getKey(), properties(entry.getKey(), entry.getValue(), roles, conflictCounts, conflictExamples)));
            }
            store.writeNodes(scope, node.label(), rows);
            nodeCounts.put(node.label(), (long) rows.size());
            conflictCounts.forEach((column, count) -> findings.add(new Finding("conflict", "PROPERTY_CONFLICT",
                node.label() + "." + column + ": " + count + " node(s) have several different values. No value was chosen; "
                    + "they are stored as " + column + "__candidates and the node is marked _conflict.",
                conflictExamples.get(column))));
        }

        // Relationships: both ends must exist; a missing start is a reference to something unknown.
        Map<String, Long> relationshipCounts = new LinkedHashMap<>();
        for (RelType relationship : schema.relationships()) {
            Map<String, NodeAgg> starts = nodes.get(relationship.fromLabel());
            Map<String, NodeAgg> ends = nodes.get(relationship.toLabel());
            List<String[]> valid = new ArrayList<>();
            long orphans = 0;
            Set<String> orphanKeys = new LinkedHashSet<>();
            for (String[] pair : pairs.get(relationship)) {
                if (!ends.containsKey(pair[1])) {
                    continue;
                }
                if (starts.containsKey(pair[0])) {
                    valid.add(pair);
                } else {
                    orphans++;
                    if (orphanKeys.size() < MAX_EXAMPLES) {
                        orphanKeys.add(pair[0]);
                    }
                }
            }
            store.writeRelationships(scope, relationship.type(), relationship.fromLabel(), relationship.toLabel(), valid);
            relationshipCounts.merge(relationship.type() + " (" + relationship.fromLabel() + "→" + relationship.toLabel() + ")", (long) valid.size(), Long::sum);
            if (orphans > 0) {
                findings.add(new Finding("warning", "MISSING_TARGET", orphans + " " + relationship.toLabel() + " refer to a "
                    + relationship.fromLabel() + " that does not exist (" + relationship.table() + "." + relationship.fromColumn()
                    + "). The relationship is not drawn for them.", new ArrayList<>(orphanKeys)));
            }
        }
        long totalRelationships = relationshipCounts.values().stream().mapToLong(Long::longValue).sum();
        findings.add(new Finding("info", "BUILT", nodeCounts.values().stream().mapToLong(Long::longValue).sum() + " node(s) and "
            + totalRelationships + " relationship(s) written to Neo4j.", List.of()));
        return new BuildResult(scope, nodeCounts, relationshipCounts, findings, (System.nanoTime() - started) / 1_000_000, Instant.now().toString());
    }

    /** The key of one end of a relationship: the value of the column, or "row-N" when the column names a line. */
    private static String endOf(List<String> record, Map<String, Integer> index, String column, boolean byPosition, long rowNumber) {
        String key = keyOf(record, index, column, rowNumber);
        if (!byPosition || key.isEmpty()) {
            return key;
        }
        String digits = key.endsWith(".0") ? key.substring(0, key.length() - 2) : key;
        return digits.matches("[0-9]{1,15}") ? "row-" + Long.parseLong(digits) : "";
    }

    private static String keyOf(List<String> record, Map<String, Integer> index, String column, long rowNumber) {
        return column == null ? "row-" + rowNumber : GraphModeler.keyValue(record, index, column);
    }

    private Map<String, Object> properties(String key, NodeAgg agg, Map<String, String> roles,
                                           Map<String, Long> conflictCounts, Map<String, List<String>> conflictExamples) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("_rows", agg.rows);
        List<String> conflicting = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : agg.values.entrySet()) {
            String column = entry.getKey();
            Set<String> values = entry.getValue();
            if (values.size() == 1) {
                props.put(column, typed(values.iterator().next(), roles.get(column)));
            } else {
                props.put(column + "__candidates", new ArrayList<>(values));
                conflicting.add(column);
                conflictCounts.merge(column, 1L, Long::sum);
                List<String> examples = conflictExamples.computeIfAbsent(column, k -> new ArrayList<>());
                if (examples.size() < MAX_EXAMPLES) {
                    examples.add(key + ": " + String.join(" | ", values));
                }
            }
        }
        if (!conflicting.isEmpty()) {
            props.put("_conflict", true);
            props.put("_conflicts", conflicting);
        }
        props.putIfAbsent("name", key);
        return props;
    }

    /** Numbers stay numbers when the column is an amount or a quantity and the value reads as one. */
    private static Object typed(String value, String role) {
        if ("AMOUNT".equals(role) || "QUANTITY".equals(role)) {
            String cleaned = value.replace(" ", "").replace(' ', ' ').strip();
            try {
                double number = Double.parseDouble(cleaned.replace(',', '.'));
                if (!Double.isNaN(number) && !Double.isInfinite(number)) {
                    return number == Math.rint(number) && Math.abs(number) < 1e15 && !cleaned.contains(".") && !cleaned.contains(",")
                        ? (Object) (long) number : (Object) number;
                }
            } catch (NumberFormatException e) {
                // not a number: kept as text
            }
        }
        return value;
    }
}
