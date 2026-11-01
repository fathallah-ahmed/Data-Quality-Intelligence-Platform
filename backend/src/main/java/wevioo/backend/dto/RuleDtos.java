package wevioo.backend.dto;

import java.time.Instant;
import java.util.List;

public class RuleDtos {
    /**
     * @param type         a {@code RuleType} name, e.g. NOT_NULL or MAX_MISSING_PERCENT
     * @param severity     WARNING (default) or CRITICAL
     * @param format       for MIN_FORMAT_VALID_PERCENT: a {@code RuleFormat} name, e.g. EMAIL
     * @param secondColumn for DATE_ORDER: the column that must not come before {@code columnName}
     */
    public record RuleRequest(
        String tableName,
        String columnName,
        String type,
        Double threshold,
        String severity,
        Boolean enabled,
        String format,
        String secondColumn
    ) {}

    /**
     * @param state  ACTIVE (checked), SUGGESTED (waiting for the user) or DISMISSED
     * @param origin MANUAL or AI
     */
    public record RuleView(
        Long id,
        String tableName,
        String columnName,
        String type,
        Double threshold,
        String severity,
        boolean enabled,
        String description,
        Instant createdAt,
        String format,
        String secondColumn,
        String state,
        String origin,
        String rationale
    ) {}

    /**
     * @param status PASS, FAIL or SKIPPED
     * @param actual the measured value the rule was compared with (null when skipped)
     */
    public record RuleResultView(
        Long ruleId,
        String tableName,
        String columnName,
        String type,
        String severity,
        String description,
        String status,
        Double actual,
        String message,
        Instant evaluatedAt
    ) {}

    public record RuleSummary(
        int total,
        int passed,
        int failed,
        int skipped,
        int criticalFailed
    ) {}

    public record RuleResults(
        RuleSummary summary,
        List<RuleResultView> results
    ) {}
}
