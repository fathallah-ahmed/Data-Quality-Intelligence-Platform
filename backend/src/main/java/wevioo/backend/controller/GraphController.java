package wevioo.backend.controller;

import org.springframework.web.bind.annotation.*;
import wevioo.backend.dto.GraphDtos.BuildRequest;
import wevioo.backend.dto.GraphDtos.GraphBuild;
import wevioo.backend.dto.GraphDtos.GraphStatus;
import wevioo.backend.graph.GraphModel.Schema;
import wevioo.backend.service.GraphService;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** The graph of a version: its proposed model, its build in Neo4j and the state of Neo4j. */
@RestController
@RequestMapping("/api/datasets/{datasetId}/versions/{versionId}/graph")
public class GraphController {
    private final GraphService graph;

    public GraphController(GraphService graph) {
        this.graph = graph;
    }

    /** Whether Neo4j can be used, and the address of its browser. */
    @GetMapping("/status")
    public GraphStatus status(@PathVariable Long datasetId, @PathVariable Long versionId) {
        return graph.status(datasetId, versionId);
    }

    /**
     * How the rows would become a graph. {@code promote} lists columns to make nodes of their own;
     * {@code refine} asks the local model to name the parts in the user's vocabulary.
     */
    @GetMapping("/schema")
    public Schema schema(
        @PathVariable Long datasetId,
        @PathVariable Long versionId,
        @RequestParam(value = "promote", required = false) String promote,
        @RequestParam(value = "refine", defaultValue = "false") boolean refine
    ) {
        Set<String> columns = promote == null || promote.isBlank()
            ? Set.of()
            : new LinkedHashSet<>(Arrays.stream(promote.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
        return graph.schema(datasetId, versionId, columns, refine);
    }

    /** Builds the graph in Neo4j, replacing the one built before for this version. */
    @PostMapping("/build")
    public GraphBuild build(@PathVariable Long datasetId, @PathVariable Long versionId, @RequestBody(required = false) BuildRequest request) {
        return graph.build(datasetId, versionId, request);
    }

    /** The last build of this version (empty when there is none). */
    @GetMapping
    public Map<String, Object> last(@PathVariable Long datasetId, @PathVariable Long versionId) {
        return graph.last(datasetId, versionId);
    }
}
