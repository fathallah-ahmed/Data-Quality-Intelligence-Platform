package wevioo.backend.graph;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** The shapes the graph of a dataset version is described with. */
public final class GraphModel {
    private GraphModel() {}

    /**
     * Joins the names of the columns that together identify a row ("order_id + item_id"). A key made of one column is
     * just that column's name.
     */
    public static final String KEY_JOIN = " + ";

    /** The columns a key is made of (one for an ordinary key, none when there is no key). */
    public static List<String> keyParts(String keyColumn) {
        return keyColumn == null ? List.of() : List.of(keyColumn.split(java.util.regex.Pattern.quote(KEY_JOIN)));
    }

    // ------------------------------------------------------------------ what the model is built from

    /** A column as the analysis understood it. */
    public record Column(String name, String role, double confidence, long distinct, long missing) {}

    /** A relationship between two tables found by the pipeline from the overlap of their values. */
    public record Link(String sourceTable, String sourceColumn, String targetTable, String targetColumn) {}

    public record TableInput(String name, Path file, long rows, List<Column> columns) {}

    // ------------------------------------------------------------------ the proposed model

    public record Property(String column, String role) {}

    /**
     * A kind of node.
     *
     * @param keyColumn the column whose value identifies a node; null when the table has none, in which
     *                  case each row is its own node
     * @param derived   the node does not stand for the rows of the table but for something they refer to
     *                  (a customer, in a table of accounts)
     * @param origin    why this node exists, in words
     */
    public record NodeType(String label, String table, String keyColumn, boolean derived, String origin, String description, List<Property> properties) {}

    /**
     * A kind of relationship, read from the rows of {@code table}: {@code fromColumn} holds the key of the
     * node it starts from and {@code toColumn} the key of the node it ends at (null means the row itself).
     * When an end is "by position", the column holds the line number (1 for the first row after the header) of a
     * row of a table that has no identifier, and the node it names is that row.
     */
    public record RelType(String type, String fromLabel, String toLabel, String table, String fromColumn, String toColumn, String basis,
                          boolean fromByPosition, boolean toByPosition) {
        public RelType(String type, String fromLabel, String toLabel, String table, String fromColumn, String toColumn, String basis) {
            this(type, fromLabel, toLabel, table, fromColumn, toColumn, basis, false, false);
        }
    }

    /** Something found while modelling or building that the user should know: info, warning or conflict. */
    public record Finding(String severity, String kind, String message, List<String> examples) {}

    /** A column that could become a node of its own (a country, a status). */
    public record Promotable(String table, String column, long distinct) {}

    /**
     * @param renames labels and relationship types that were renamed from their default name (old to new)
     */
    public record Schema(
        List<NodeType> nodes,
        List<RelType> relationships,
        List<Finding> findings,
        List<Promotable> promotable,
        Map<String, String> renames
    ) {}

    // ------------------------------------------------------------------ what a build produced

    public record BuildResult(
        String scope,
        Map<String, Long> nodes,
        Map<String, Long> relationships,
        List<Finding> findings,
        long durationMs,
        String builtAt
    ) {}
}
