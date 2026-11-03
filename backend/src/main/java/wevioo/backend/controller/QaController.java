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
}
