package wevioo.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import wevioo.backend.dto.DiffDtos.VersionDiff;
import wevioo.backend.dto.RuleDtos.*;
import wevioo.backend.service.RuleService;
import wevioo.backend.service.VersionDiffService;

import java.util.List;
import java.util.Map;

/** Quality rules, their results per version, and the comparison of two versions. */
@RestController
@RequestMapping("/api/datasets/{datasetId}")
public class RuleController {
    private final RuleService ruleService;
    private final VersionDiffService diffService;

    public RuleController(RuleService ruleService, VersionDiffService diffService) {
        this.ruleService = ruleService;
        this.diffService = diffService;
    }

    @GetMapping("/rules")
    public List<RuleView> listRules(@PathVariable Long datasetId) {
        return ruleService.list(datasetId);
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleView createRule(@PathVariable Long datasetId, @RequestBody RuleRequest request) {
        return ruleService.create(datasetId, request);
    }

    @PutMapping("/rules/{ruleId}")
    public RuleView updateRule(@PathVariable Long datasetId, @PathVariable Long ruleId, @RequestBody RuleRequest request) {
        return ruleService.update(datasetId, ruleId, request);
    }

    @DeleteMapping("/rules/{ruleId}")
    public Map<String, String> deleteRule(@PathVariable Long datasetId, @PathVariable Long ruleId) {
        ruleService.delete(datasetId, ruleId);
        return Map.of("status", "deleted");
    }

    @GetMapping("/versions/{versionId}/rule-results")
    public RuleResults ruleResults(@PathVariable Long datasetId, @PathVariable Long versionId) {
        return ruleService.results(datasetId, versionId);
    }

    /** Re-check the rules against a version, e.g. after adding or editing a rule. */
    @PostMapping("/versions/{versionId}/rules/evaluate")
    public RuleResults evaluate(@PathVariable Long datasetId, @PathVariable Long versionId) {
        return ruleService.evaluate(datasetId, versionId);
    }

    /**
     * Compare a version with an older one (the previous version unless {@code against} is given):
     * row, column and score deltas and schema drift per table.
     */
    @GetMapping("/versions/{versionId}/diff")
    public VersionDiff diff(
        @PathVariable Long datasetId,
        @PathVariable Long versionId,
        @RequestParam(value = "against", required = false) Long againstVersionId
    ) {
        return diffService.diff(datasetId, versionId, againstVersionId);
    }
}
