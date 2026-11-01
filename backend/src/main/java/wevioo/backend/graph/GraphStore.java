package wevioo.backend.graph;

import java.util.List;
import java.util.Map;

/** Where the graph of a version is written. Everything is tied to a scope (one dataset version) so builds never mix. */
public interface GraphStore {
    /** Neo4j cannot be reached or refuses the credentials. */
    class GraphUnavailableException extends RuntimeException {
        public GraphUnavailableException(String message) {
            super(message);
        }
    }

    /** A node: its key (unique for its label inside the scope) and its properties. */
    record NodeRow(String key, Map<String, Object> properties) {}

    /** Throws {@link GraphUnavailableException} when the database cannot be used. */
    void ping();

    /** Removes everything built for one scope. */
    void clear(String scope);

    /** Removes everything built for every scope that starts with the prefix (all versions of a dataset). */
    void clearPrefix(String prefix);

    void writeNodes(String scope, String label, List<NodeRow> nodes);

    /** @param pairs {from key, to key} */
    void writeRelationships(String scope, String type, String fromLabel, String toLabel, List<String[]> pairs);
}
