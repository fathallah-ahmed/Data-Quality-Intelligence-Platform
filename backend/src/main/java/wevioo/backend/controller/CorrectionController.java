package wevioo.backend.controller;

import org.springframework.web.bind.annotation.*;
import wevioo.backend.dto.CorrectionDtos.*;
import wevioo.backend.dto.UploadResponse;
import wevioo.backend.service.CorrectionService;

import java.util.Map;

/** Corrections of a table of a version: suggestions, plain-language requests, preview and application. */
@RestController
@RequestMapping("/api/datasets/{datasetId}/versions/{versionId}/corrections")
public class CorrectionController {
    private final CorrectionService corrections;

    public CorrectionController(CorrectionService corrections) {
        this.corrections = corrections;
    }

    /** Corrections found by reading the table: spaces, placeholders, date formats, spellings, repeated rows. */
    @GetMapping("/suggestions")
    public Suggestions suggestions(@PathVariable Long datasetId, @PathVariable Long versionId, @RequestParam String table) {
        return corrections.suggestions(datasetId, versionId, table);
    }

    /** Reads a request written in plain language and returns the corrections it means. Nothing is changed. */
    @PostMapping("/interpret")
    public Interpretation interpret(@PathVariable Long datasetId, @PathVariable Long versionId, @RequestBody InterpretRequest request) {
        return corrections.interpret(datasetId, versionId, request);
    }

    /** What the corrections would change, with counts and examples. Nothing is written. */
    @PostMapping("/preview")
    public Preview preview(@PathVariable Long datasetId, @PathVariable Long versionId, @RequestBody PlanRequest request) {
        return corrections.preview(datasetId, versionId, request);
    }

    /** Applies the corrections to a copy: the result is the next version of the dataset. */
    @PostMapping("/apply")
    public UploadResponse apply(@PathVariable Long datasetId, @PathVariable Long versionId, @RequestBody PlanRequest request) {
        return corrections.apply(datasetId, versionId, request);
    }

    /** What was corrected to make this version (empty for an uploaded version). */
    @GetMapping
    public Map<String, Object> log(@PathVariable Long datasetId, @PathVariable Long versionId) {
        return corrections.log(datasetId, versionId);
    }
}
