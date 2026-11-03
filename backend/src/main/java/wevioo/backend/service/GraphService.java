package wevioo.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import wevioo.backend.config.AppProperties;
import wevioo.backend.correction.TableFiles;
import wevioo.backend.domain.ChangeStatus;
import wevioo.backend.domain.ColumnMetric;
import wevioo.backend.domain.TableAnalysis;
import wevioo.backend.domain.TableVersion;
import wevioo.backend.dto.GraphDtos.BuildRequest;
import wevioo.backend.dto.GraphDtos.GraphBuild;
import wevioo.backend.dto.GraphDtos.GraphStatus;
import wevioo.backend.graph.GraphBuilder;
import wevioo.backend.graph.GraphModel.*;
import wevioo.backend.graph.GraphModeler;
import wevioo.backend.graph.GraphNamer;
import wevioo.backend.graph.GraphStore;
import wevioo.backend.repository.TableVersionRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The graph of a dataset version: how its rows could become nodes and relationships, and the build of that
 * graph in Neo4j. The model of the graph comes from the roles of the columns and from the data; the version's
 * files are only read.
 */
@Service
public class GraphService {
    static final String RESULT_FILE = "graph_build.json";

    private final DatasetService datasetService;
    private final TableVersionRepository tableVersionRepository;
    private final GraphModeler modeler;
    private final GraphBuilder builder;
    private final GraphNamer namer;
    private final GraphStore store;
    private final JsonService json;
    private final AppProperties properties;
    private final TransactionTemplate transactions;

    public GraphService(
        DatasetService datasetService,
        TableVersionRepository tableVersionRepository,
        GraphModeler modeler,
        GraphBuilder builder,
        GraphNamer namer,
        GraphStore store,
        JsonService json,
        AppProperties properties,
        PlatformTransactionManager transactionManager
    ) {
        this.datasetService = datasetService;
        this.tableVersionRepository = tableVersionRepository;
        this.modeler = modeler;
        this.builder = builder;
        this.namer = namer;
        this.store = store;
        this.json = json;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setReadOnly(true);
    }

    /** What the analysis found, as the model of the graph is built from it. */
    private record Loaded(List<TableInput> tables, List<Link> links, Map<String, String> context, Path folder) {}

    public static String scope(Long datasetId, Long versionId) {
        return "d" + datasetId + "v" + versionId;
    }

    public GraphStatus status(Long datasetId, Long versionId) {
        String browser = properties.getNeo4jUri().replaceFirst("^(bolt|neo4j)(\\+s?c?)?://", "http://").replaceFirst(":\\d+$", ":7474");
        try {
            store.ping();
            return new GraphStatus(true, "Neo4j is ready.", browser, scope(datasetId, versionId));
        } catch (GraphStore.GraphUnavailableException e) {
            return new GraphStatus(false, e.getMessage(), browser, scope(datasetId, versionId));
        }
    }

    /** The model of the graph for this version. With {@code refine}, the local model proposes names for it. */
    public Schema schema(Long datasetId, Long versionId, Set<String> promote, boolean refine) {
        Loaded loaded = load(datasetId, versionId);
        Schema schema = propose(loaded, promote);
        return refine ? namer.refine(schema, loaded.context()) : schema;
    }

    /** Builds the graph in Neo4j (replacing the one built before for this version) and remembers the result. */
    public GraphBuild build(Long datasetId, Long versionId, BuildRequest request) {
        store.ping();
        Loaded loaded = load(datasetId, versionId);
        Set<String> promote = request == null || request.promote() == null ? Set.of() : new HashSet<>(request.promote());
        Schema schema = GraphNamer.applyRenames(propose(loaded, promote), request == null ? null : request.renames());
        BuildResult result;
        try {
            result = builder.build(scope(datasetId, versionId), schema, loaded.tables());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        GraphBuild build = new GraphBuild(schema, result);
        try {
            Files.writeString(loaded.folder().resolve(RESULT_FILE), json.toJson(build));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return build;
    }

    /** The last build of this version, or an empty map when none was made. */
    public Map<String, Object> last(Long datasetId, Long versionId) {
        Path file = datasetService.versionFolder(datasetId, versionId).resolve(RESULT_FILE);
        return Files.isRegularFile(file) ? json.readMap(file) : Map.of();
    }

    private Schema propose(Loaded loaded, Set<String> promote) {
        try {
            return modeler.propose(loaded.tables(), loaded.links(), promote);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Loaded load(Long datasetId, Long versionId) {
        Path folder = datasetService.versionFolder(datasetId, versionId);
        Loaded loaded = transactions.execute(status -> read(versionId, folder));
        if (loaded == null || loaded.tables().isEmpty()) {
            throw new IllegalArgumentException("This version has no analysed table yet, so there is nothing to build a graph from");
        }
        return loaded;
    }

    private Loaded read(Long versionId, Path folder) {
        List<TableInput> tables = new ArrayList<>();
        List<Link> links = new ArrayList<>();
        for (TableVersion tableVersion : tableVersionRepository.findByDatasetVersionIdOrderByTableNameAsc(versionId)) {
            TableAnalysis analysis = tableVersion.getAnalysis();
            if (tableVersion.getChangeStatus() == ChangeStatus.REMOVED || analysis == null || analysis.getRowCount() == null) {
                continue;
            }
            List<Column> columns = new ArrayList<>();
            for (ColumnMetric metric : analysis.getColumnMetrics()) {
                columns.add(new Column(
                    metric.getColumnName().strip(),
                    metric.getRole() == null ? "UNKNOWN" : metric.getRole(),
                    metric.getRoleConfidence() == null ? 0 : metric.getRoleConfidence(),
                    metric.getUniqueCount() == null ? 0 : metric.getUniqueCount(),
                    metric.getMissingCount() == null ? 0 : metric.getMissingCount()
                ));
            }
            tables.add(new TableInput(tableVersion.getTableName(), TableFiles.find(folder, tableVersion.getTableName()), analysis.getRowCount(), columns));
            for (Object item : json.toList(analysis.getRelationshipsJson())) {
                if (item instanceof Map<?, ?> relation && relation.get("source_table") != null && relation.get("target_table") != null) {
                    links.add(new Link(
                        String.valueOf(relation.get("source_table")), String.valueOf(relation.get("source_column")).strip(),
                        String.valueOf(relation.get("target_table")), String.valueOf(relation.get("target_column")).strip()));
                }
            }
        }
        return new Loaded(tables, links, context(folder), folder);
    }

    /** The domain, goal and row grain the user gave at upload (empty when none was given). */
    public Map<String, String> businessContext(Long datasetId, Long versionId) {
        return context(datasetService.versionFolder(datasetId, versionId));
    }

    private Map<String, String> context(Path folder) {
        Map<String, String> context = new LinkedHashMap<>();
        Path file = folder.resolve("dataset_context.json");
        if (Files.isRegularFile(file)) {
            json.readMap(file).forEach((key, value) -> {
                if (value != null && !String.valueOf(value).isBlank()) {
                    context.put(key, String.valueOf(value));
                }
            });
        }
        return context;
    }
}
