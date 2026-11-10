package wevioo.backend.controller;

import org.springframework.web.bind.annotation.*;
import wevioo.backend.qa.QaService;

import java.util.Map;

/** Questions in plain language about the graph of a version. */
@RestController
@RequestMapping("/api/datasets/{datasetId}/versions/{versionId}/ask")
public class QaController {
    private final QaService qa;

    public QaController(QaService qa) {
        this.qa = qa;
    }

    /** Body: {"question": "..."}. The graph of the version must have been built (Graph tab). */
    @PostMapping
    public Map<String, Object> ask(@PathVariable Long datasetId, @PathVariable Long versionId, @RequestBody Map<String, Object> request) {
        Object question = request == null ? null : request.get("question");
        return qa.ask(datasetId, versionId, question == null ? null : String.valueOf(question));
    }

    /** Body: {"question": "...", "cypher": "..."}. Returns the nodes and relationships behind that answer. */
    @PostMapping("/subgraph")
    public Map<String, Object> subgraph(@PathVariable Long datasetId, @PathVariable Long versionId, @RequestBody Map<String, Object> request) {
        return qa.subgraph(datasetId, versionId, text(request, "question"), text(request, "cypher"));
    }

    /** Body: {"nodeId": "..."}. Returns the neighbours of that node. */
    @PostMapping("/expand")
    public Map<String, Object> expand(@PathVariable Long datasetId, @PathVariable Long versionId, @RequestBody Map<String, Object> request) {
        return qa.expand(datasetId, versionId, text(request, "nodeId"));
    }

    private static String text(Map<String, Object> request, String key) {
        Object value = request == null ? null : request.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
