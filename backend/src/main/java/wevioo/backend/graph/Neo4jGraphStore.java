package wevioo.backend.graph;

import jakarta.annotation.PreDestroy;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.exceptions.Neo4jException;
import org.springframework.stereotype.Component;
import wevioo.backend.config.AppProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Neo4j over Bolt. Labels and relationship types cannot be passed as parameters in Cypher, so they are
 * written into the statement: they are checked against a strict pattern first, so a name can never
 * carry Cypher. Everything else (keys, properties, scopes) travels as parameters.
 */
@Component
public class Neo4jGraphStore implements GraphStore {
    private static final Pattern LABEL = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,60}$");
    private static final Pattern TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,60}$");
    private static final int BATCH = 1000;

    private final AppProperties properties;
    private volatile Driver driver;

    public Neo4jGraphStore(AppProperties properties) {
        this.properties = properties;
    }

    private Driver driver() {
        Driver current = driver;
        if (current == null) {
            synchronized (this) {
                if (driver == null) {
                    driver = GraphDatabase.driver(
                        properties.getNeo4jUri(),
                        AuthTokens.basic(properties.getNeo4jUser(), properties.getNeo4jPassword()),
                        Config.builder()
                            .withConnectionTimeout(5, TimeUnit.SECONDS)
                            .withConnectionAcquisitionTimeout(8, TimeUnit.SECONDS)
                            .build()
                    );
                }
                current = driver;
            }
        }
        return current;
    }

    private <T> T inSession(Function<Session, T> work) {
        try (Session session = driver().session()) {
            return work.apply(session);
        } catch (Neo4jException | IllegalStateException e) {
            throw new GraphUnavailableException("Neo4j cannot be used at " + properties.getNeo4jUri()
                + " (is it running? docker compose -f docker-compose.neo4j.yml up -d). " + rootMessage(e));
        }
    }

    @Override
    public void ping() {
        try {
            driver().verifyConnectivity();
        } catch (Neo4jException | IllegalStateException e) {
            throw new GraphUnavailableException("Neo4j cannot be reached at " + properties.getNeo4jUri()
                + " (is it running? docker compose -f docker-compose.neo4j.yml up -d). " + rootMessage(e));
        }
    }

    @Override
    public List<String> scopes() {
        return inSession(session -> session.executeRead(tx -> tx.run(
            "MATCH (n) WHERE n._scope IS NOT NULL RETURN DISTINCT n._scope AS scope ORDER BY scope").list(r -> r.get("scope").asString())));
    }

    @Override
    public void clear(String scope) {
        deleteWhere("n._scope = $value", scope);
    }

    @Override
    public void clearPrefix(String prefix) {
        deleteWhere("n._scope STARTS WITH $value", prefix);
    }

    private void deleteWhere(String condition, String value) {
        inSession(session -> {
            long deleted;
            do {
                deleted = session.executeWrite(tx -> tx.run(
                    "MATCH (n) WHERE " + condition + " WITH n LIMIT 10000 DETACH DELETE n RETURN count(*) AS deleted",
                    Map.of("value", value)).single().get("deleted").asLong());
            } while (deleted > 0);
            return null;
        });
    }

    @Override
    public void writeNodes(String scope, String label, List<NodeRow> nodes) {
        requireLabel(label);
        inSession(session -> {
            session.executeWrite(tx -> {
                tx.run("CREATE INDEX `dq_" + label + "_scope_key` IF NOT EXISTS FOR (n:`" + label + "`) ON (n._scope, n.key)").consume();
                return null;
            });
            for (int from = 0; from < nodes.size(); from += BATCH) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (NodeRow node : nodes.subList(from, Math.min(nodes.size(), from + BATCH))) {
                    rows.add(Map.of("key", node.key(), "props", node.properties()));
                }
                session.executeWrite(tx -> {
                    tx.run("UNWIND $rows AS row MERGE (n:`" + label + "` {_scope: $scope, key: row.key}) SET n += row.props",
                        Map.of("rows", rows, "scope", scope)).consume();
                    return null;
                });
            }
            return null;
        });
    }

    @Override
    public void writeRelationships(String scope, String type, String fromLabel, String toLabel, List<String[]> pairs) {
        requireType(type);
        requireLabel(fromLabel);
        requireLabel(toLabel);
        inSession(session -> {
            for (int from = 0; from < pairs.size(); from += BATCH) {
                List<List<String>> rows = new ArrayList<>();
                for (String[] pair : pairs.subList(from, Math.min(pairs.size(), from + BATCH))) {
                    rows.add(List.of(pair[0], pair[1]));
                }
                session.executeWrite(tx -> {
                    tx.run("UNWIND $rows AS p MATCH (a:`" + fromLabel + "` {_scope: $scope, key: p[0]}) "
                            + "MATCH (b:`" + toLabel + "` {_scope: $scope, key: p[1]}) "
                            + "MERGE (a)-[r:`" + type + "`]->(b) SET r._scope = $scope",
                        Map.of("rows", rows, "scope", scope)).consume();
                    return null;
                });
            }
            return null;
        });
    }

    private static void requireLabel(String label) {
        if (label == null || !LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException("Invalid node label: " + label);
        }
    }

    private static void requireType(String type) {
        if (type == null || !TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("Invalid relationship type: " + type);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @PreDestroy
    void close() {
        Driver current = driver;
        if (current != null) {
            current.close();
        }
    }
}
