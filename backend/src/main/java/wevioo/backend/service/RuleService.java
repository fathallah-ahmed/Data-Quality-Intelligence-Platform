package wevioo.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wevioo.backend.domain.*;
import wevioo.backend.dto.LiveEvent;
import wevioo.backend.dto.PipelineDtos.SuggestedRule;
import wevioo.backend.dto.RuleDtos.*;
import wevioo.backend.exception.NotFoundException;
import wevioo.backend.repository.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Data-quality rules: management, and evaluation against the metrics of a dataset version.
 * Rules are evaluated automatically when a run finishes, and on demand.
 */
@Service
public class RuleService {
    private static final Logger log = LoggerFactory.getLogger(RuleService.class);

    private final DatasetRepository datasetRepository;
    private final DatasetVersionRepository versionRepository;
    private final TableVersionRepository tableVersionRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final QualityRuleRepository ruleRepository;
    private final RuleResultRepository resultRepository;
    private final JsonService jsonService;

    public RuleService(
        DatasetRepository datasetRepository,
        DatasetVersionRepository versionRepository,
        TableVersionRepository tableVersionRepository,
        PipelineRunRepository pipelineRunRepository,
        QualityRuleRepository ruleRepository,
        RuleResultRepository resultRepository,
        JsonService jsonService
    ) {
        this.datasetRepository = datasetRepository;
        this.versionRepository = versionRepository;
        this.tableVersionRepository = tableVersionRepository;
        this.pipelineRunRepository = pipelineRunRepository;
        this.ruleRepository = ruleRepository;
        this.resultRepository = resultRepository;
        this.jsonService = jsonService;
    }

    // ------------------------------------------------------------------ management

    /** The rules to show: active ones and suggestions waiting for a decision (turned-down ones are hidden). */
    @Transactional(readOnly = true)
    public List<RuleView> list(Long datasetId) {
        requireDataset(datasetId);
        return ruleRepository.findByDatasetIdOrderByIdAsc(datasetId).stream()
            .filter(rule -> rule.getState() != RuleState.DISMISSED)
            .map(RuleService::toView)
            .toList();
    }

    /** Switch on a suggested rule: from now on it is checked on every version. */
    @Transactional
    public RuleView approve(Long datasetId, Long ruleId) {
        QualityRule rule = requireRule(datasetId, ruleId);
        rule.setState(RuleState.ACTIVE);
        rule.setEnabled(true);
        return toView(rule);
    }

    /** Turn down a suggested rule; it is hidden and the same suggestion is not made again. */
    @Transactional
    public void dismiss(Long datasetId, Long ruleId) {
        requireRule(datasetId, ruleId).setState(RuleState.DISMISSED);
    }

    /**
     * Store the rules the pipeline proposed from the user's goal, as suggestions waiting for approval.
     * A suggestion that is invalid, or that repeats a rule the dataset already has (even a turned-down
     * one), is dropped. Returns the live event when something new was added.
     */
    @Transactional
    public Optional<LiveEvent> saveSuggestions(String externalRunId, List<SuggestedRule> suggestions) {
        PipelineRun run = pipelineRunRepository.findByExternalRunId(externalRunId)
            .orElseThrow(() -> new NotFoundException("Unknown pipeline run: " + externalRunId));
        DatasetVersion version = run.getDatasetVersion();
        Dataset dataset = version.getDataset();
        List<QualityRule> known = new ArrayList<>(ruleRepository.findByDatasetIdOrderByIdAsc(dataset.getId()));
        int added = 0;
        for (SuggestedRule suggestion : suggestions == null ? List.<SuggestedRule>of() : suggestions) {
            QualityRule rule = new QualityRule();
            try {
                apply(rule, new RuleRequest(
                    suggestion.tableName(), suggestion.columnName(), suggestion.type(), suggestion.threshold(),
                    suggestion.severity(), true, suggestion.format(), suggestion.secondColumn()
                ));
            } catch (IllegalArgumentException e) {
                log.info("Dropped an invalid rule suggestion for run {}: {}", externalRunId, e.getMessage());
                continue;
            }
            if (known.stream().anyMatch(existing -> sameCheck(existing, rule))) {
                continue;
            }
            rule.setDataset(dataset);
            rule.setState(RuleState.SUGGESTED);
            rule.setOrigin(RuleOrigin.AI);
            rule.setRationale(clip(suggestion.rationale(), 1000));
            known.add(ruleRepository.save(rule));
            added++;
        }
        if (added == 0) {
            return Optional.empty();
        }
        return Optional.of(new LiveEvent(
            "RULES_SUGGESTED", dataset.getId(), version.getId(), version.getVersionNumber(), externalRunId,
            run.getStatus().name(), null, added + " quality rule(s) suggested from your goal", Instant.now()
        ));
    }

    private static boolean sameCheck(QualityRule a, QualityRule b) {
        return a.getType() == b.getType()
            && Objects.equals(a.getTableName(), b.getTableName())
            && Objects.equals(a.getColumnName(), b.getColumnName())
            && Objects.equals(a.getFormat(), b.getFormat())
            && Objects.equals(a.getSecondColumnName(), b.getSecondColumnName());
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    @Transactional
    public RuleView create(Long datasetId, RuleRequest request) {
        Dataset dataset = requireDataset(datasetId);
        QualityRule rule = new QualityRule();
        rule.setDataset(dataset);
        apply(rule, request);
        return toView(ruleRepository.save(rule));
    }

    @Transactional
    public RuleView update(Long datasetId, Long ruleId, RuleRequest request) {
        QualityRule rule = requireRule(datasetId, ruleId);
        apply(rule, request);
        return toView(rule);
    }

    @Transactional
    public void delete(Long datasetId, Long ruleId) {
        QualityRule rule = requireRule(datasetId, ruleId);
        resultRepository.deleteByRule(ruleId);
        ruleRepository.delete(ruleRepository.getReferenceById(rule.getId()));
    }

    private void apply(QualityRule rule, RuleRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("The rule is missing");
        }
        RuleType type = parse(RuleType.class, request.type(), "rule type");
        String table = request.tableName() == null ? "" : request.tableName().trim();
        String column = request.columnName() == null ? "" : request.columnName().trim();
        if (table.isEmpty()) {
            throw new IllegalArgumentException("Choose the table the rule applies to");
        }
        if (type.needsColumn() && column.isEmpty()) {
            throw new IllegalArgumentException("This rule type needs a column");
        }
        Double threshold = request.threshold();
        if (type == RuleType.DATE_ORDER && threshold == null) {
            threshold = 0.0;
        }
        RuleFormat format = null;
        if (type.needsFormat()) {
            format = parse(RuleFormat.class, request.format(), "format");
        }
        String secondColumn = request.secondColumn() == null ? "" : request.secondColumn().trim();
        if (type.needsSecondColumn()) {
            if (secondColumn.isEmpty()) {
                throw new IllegalArgumentException("This rule type needs a second column");
            }
            if (secondColumn.equals(column)) {
                throw new IllegalArgumentException("The two columns must be different");
            }
        }
        if (type.needsThreshold()) {
            if (threshold == null || threshold.isNaN() || threshold.isInfinite() || threshold < 0) {
                throw new IllegalArgumentException("This rule type needs a threshold of 0 or more");
            }
            if ((type.isPercent() || type == RuleType.MIN_QUALITY_SCORE) && threshold > 100) {
                throw new IllegalArgumentException("The threshold must be between 0 and 100");
            }
        }
        rule.setType(type);
        rule.setTableName(table);
        rule.setColumnName(type.needsColumn() ? column : null);
        rule.setThreshold(type.needsThreshold() ? threshold : null);
        rule.setFormat(format == null ? null : format.name());
        rule.setSecondColumnName(type.needsSecondColumn() ? secondColumn : null);
        rule.setSeverity(request.severity() == null || request.severity().isBlank()
            ? RuleSeverity.WARNING
            : parse(RuleSeverity.class, request.severity(), "severity"));
        if (request.enabled() != null) {
            rule.setEnabled(request.enabled());
        }
    }

    // ------------------------------------------------------------------ results

    @Transactional(readOnly = true)
    public RuleResults results(Long datasetId, Long versionId) {
        requireVersion(datasetId, versionId);
        return toResults(resultRepository.findByDatasetVersionIdOrderByIdAsc(versionId));
    }

    /** Evaluate every enabled rule of the dataset against one version and store the outcome. */
    @Transactional
    public RuleResults evaluate(Long datasetId, Long versionId) {
        requireVersion(datasetId, versionId);
        List<QualityRule> rules = ruleRepository.findByDatasetIdOrderByIdAsc(datasetId).stream()
            .filter(QualityRule::isChecked)
            .toList();
        resultRepository.deleteByVersion(versionId);

        // The bulk delete cleared the persistence context: load the version again.
        DatasetVersion version = versionRepository.findById(versionId).orElseThrow();
        Map<String, TableVersion> tables = tableVersionRepository.findByDatasetVersionIdOrderByTableNameAsc(versionId).stream()
            .filter(table -> table.getChangeStatus() != ChangeStatus.REMOVED)
            .collect(Collectors.toMap(TableVersion::getTableName, table -> table, (first, second) -> first));

        List<RuleResult> results = new ArrayList<>();
        Instant now = Instant.now();
        for (QualityRule rule : rules) {
            Outcome outcome = evaluateOne(rule, tables.get(rule.getTableName()), datasetId, version);
            RuleResult result = new RuleResult();
            result.setDatasetVersion(version);
            result.setRuleId(rule.getId());
            result.setTableName(rule.getTableName());
            result.setColumnName(rule.getColumnName());
            result.setType(rule.getType());
            result.setSeverity(rule.getSeverity());
            result.setDescription(describe(rule));
            result.setStatus(outcome.status());
            result.setActual(outcome.actual());
            result.setMessage(outcome.message());
            result.setEvaluatedAt(now);
            results.add(result);
        }
        return toResults(resultRepository.saveAll(results));
    }

    /**
     * Called when a pipeline run has finished: evaluates the rules and, when some failed, returns
     * the live event that alerts the UI. Never throws: a broken rule must not break the run.
     */
    @Transactional
    public Optional<LiveEvent> evaluateForRun(String externalRunId) {
        try {
            PipelineRun run = pipelineRunRepository.findByExternalRunId(externalRunId).orElse(null);
            if (run == null || (run.getStatus() != RunStatus.COMPLETED && run.getStatus() != RunStatus.PARTIAL)) {
                return Optional.empty();
            }
            DatasetVersion version = run.getDatasetVersion();
            Long datasetId = version.getDataset().getId();
            Long versionId = version.getId();
            Integer versionNumber = version.getVersionNumber();
            String status = run.getStatus().name();
            if (ruleRepository.findByDatasetIdOrderByIdAsc(datasetId).stream().noneMatch(QualityRule::isChecked)) {
                return Optional.empty();
            }
            RuleSummary summary = evaluate(datasetId, versionId).summary();
            if (summary.failed() == 0) {
                return Optional.empty();
            }
            String message = summary.failed() + " of " + summary.total() + " quality rule(s) failed"
                + (summary.criticalFailed() > 0 ? " (" + summary.criticalFailed() + " critical)" : "");
            return Optional.of(new LiveEvent("RULES_FAILED", datasetId, versionId, versionNumber, externalRunId, status, null, message, Instant.now()));
        } catch (RuntimeException e) {
            log.warn("Could not evaluate the quality rules of run {}: {}", externalRunId, e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ evaluation

    record Outcome(RuleStatus status, Double actual, String message) {
        static Outcome skipped(String message) {
            return new Outcome(RuleStatus.SKIPPED, null, message);
        }

        static Outcome of(boolean pass, double actual, String message) {
            return new Outcome(pass ? RuleStatus.PASS : RuleStatus.FAIL, actual, message);
        }
    }

    private Outcome evaluateOne(QualityRule rule, TableVersion table, Long datasetId, DatasetVersion version) {
        TableAnalysis analysis = table == null ? null : table.getAnalysis();
        if (analysis == null) {
            return Outcome.skipped("Table " + rule.getTableName() + " has no results in this version");
        }
        long rows = analysis.getRowCount() == null ? 0 : analysis.getRowCount();
        double threshold = rule.getThreshold() == null ? 0 : rule.getThreshold();
        RuleType type = rule.getType();

        if (type.needsColumn()) {
            ColumnMetric column = analysis.getColumnMetrics().stream()
                .filter(metric -> metric.getColumnName().equals(rule.getColumnName()))
                .findFirst()
                .orElse(null);
            if (column == null) {
                return Outcome.of(false, 0, "Column " + rule.getColumnName() + " does not exist in this version");
            }
            long missing = column.getMissingCount() == null ? 0 : column.getMissingCount();
            double missingPercent = column.getMissingPercentage() == null ? 0 : column.getMissingPercentage();
            return switch (type) {
                case NOT_NULL -> Outcome.of(missing == 0, missing, missing == 0 ? "No missing values" : missing + " missing value(s)");
                case MAX_MISSING_PERCENT -> Outcome.of(missingPercent <= threshold, missingPercent,
                    fmt(missingPercent) + "% missing (limit " + fmt(threshold) + "%)");
                case UNIQUE -> {
                    long filled = Math.max(0, rows - missing);
                    long distinct = column.getUniqueCount() == null ? 0 : column.getUniqueCount();
                    long repeated = Math.max(0, filled - distinct);
                    yield filled == 0
                        ? Outcome.skipped("The column has no values")
                        : Outcome.of(repeated == 0, repeated, repeated == 0 ? "All values are distinct" : repeated + " repeated value(s)");
                }
                case MIN_FORMAT_VALID_PERCENT -> formatOutcome(rule, column, threshold);
                case DATE_ORDER -> dateOrderOutcome(rule, analysis, threshold);
                default -> Outcome.skipped("Unsupported rule");
            };
        }

        return switch (type) {
            case MIN_ROWS -> Outcome.of(rows >= threshold, rows, rows + " row(s) (minimum " + fmt(threshold) + ")");
            case MAX_DUPLICATE_PERCENT -> {
                if (rows == 0) {
                    yield Outcome.skipped("The table has no rows");
                }
                long duplicates = analysis.getDuplicateCount() == null ? 0 : analysis.getDuplicateCount();
                double percent = duplicates * 100.0 / rows;
                yield Outcome.of(percent <= threshold, percent, fmt(percent) + "% duplicate rows (limit " + fmt(threshold) + "%)");
            }
            case MIN_QUALITY_SCORE -> analysis.getQualityScore() == null
                ? Outcome.skipped("The table has no quality score")
                : Outcome.of(analysis.getQualityScore() >= threshold, analysis.getQualityScore(),
                    "Score " + fmt(analysis.getQualityScore()) + " (minimum " + fmt(threshold) + ")");
            case MAX_ROW_DROP_PERCENT -> {
                TableAnalysis previous = previousAnalysis(datasetId, rule.getTableName(), version.getVersionNumber());
                if (previous == null || previous.getRowCount() == null || previous.getRowCount() == 0) {
                    yield Outcome.skipped("There is no earlier version of this table to compare with");
                }
                double drop = (previous.getRowCount() - rows) * 100.0 / previous.getRowCount();
                yield Outcome.of(drop <= threshold, drop,
                    previous.getRowCount() + " to " + rows + " rows (" + (drop > 0 ? "-" : "+") + fmt(Math.abs(drop)) + "%, limit -" + fmt(threshold) + "%)");
            }
            default -> Outcome.skipped("Unsupported rule");
        };
    }

    private TableAnalysis previousAnalysis(Long datasetId, String tableName, Integer versionNumber) {
        return tableVersionRepository
            .findTopByDatasetVersionDatasetIdAndTableNameAndDatasetVersionVersionNumberLessThanOrderByDatasetVersionVersionNumberDesc(
                datasetId, tableName, versionNumber)
            .filter(table -> table.getChangeStatus() != ChangeStatus.REMOVED)
            .map(TableVersion::getAnalysis)
            .orElse(null);
    }

    // ------------------------------------------------------------------ mapping

    public static String describe(QualityRule rule) {
        return describe(rule.getType(), rule.getTableName(), rule.getColumnName(), rule.getThreshold(),
            rule.getFormat(), rule.getSecondColumnName());
    }

    public static String describe(RuleType type, String table, String column, Double threshold) {
        return describe(type, table, column, threshold, null, null);
    }

    public static String describe(RuleType type, String table, String column, Double threshold, String format, String secondColumn) {
        String target = table + (column == null ? "" : "." + column);
        String limit = threshold == null ? "" : fmt(threshold);
        return switch (type) {
            case MIN_FORMAT_VALID_PERCENT -> target + " has at least " + limit + "% of its values as "
                + formatLabel(format);
            case DATE_ORDER -> target + " is not after " + table + "." + secondColumn
                + (threshold == null || threshold == 0 ? "" : " (up to " + limit + "% of rows may break this)");
            case NOT_NULL -> target + " has no missing values";
            case MAX_MISSING_PERCENT -> target + " has at most " + limit + "% missing values";
            case UNIQUE -> target + " is unique";
            case MIN_ROWS -> table + " has at least " + limit + " rows";
            case MAX_DUPLICATE_PERCENT -> table + " has at most " + limit + "% duplicate rows";
            case MIN_QUALITY_SCORE -> table + " scores at least " + limit;
            case MAX_ROW_DROP_PERCENT -> table + " loses at most " + limit + "% of its rows between versions";
        };
    }

    private static String formatLabel(String format) {
        try {
            return RuleFormat.valueOf(format).article();
        } catch (IllegalArgumentException | NullPointerException e) {
            return String.valueOf(format);
        }
    }

    // ------------------------------------------------------------------ checks that read the measured values

    /** At least `threshold` percent of the filled values must match the format the pipeline measured. */
    private Outcome formatOutcome(QualityRule rule, ColumnMetric column, double threshold) {
        Map<String, Object> measured = mapValue(jsonService.toMap(column.getFormatStatsJson()).get(rule.getFormat()));
        long checked = longValue(measured.get("checked"));
        if (measured.isEmpty() || checked == 0) {
            return Outcome.skipped("No text values were measured for " + rule.getColumnName()
                + " in this version (the column is not text, or it is empty)");
        }
        double percent = longValue(measured.get("valid")) * 100.0 / checked;
        List<Object> examples = listValue(measured.get("invalid_examples"));
        String example = percent < 100 && !examples.isEmpty() ? ", for example '" + clip(String.valueOf(examples.get(0)), 60) + "'" : "";
        return Outcome.of(percent >= threshold, percent,
            clip(fmt(percent) + "% of the filled values are " + formatLabel(rule.getFormat())
                + " (minimum " + fmt(threshold) + "%)" + example, 480));
    }

    /** On rows where both dates are filled, the first column must not be after the second. */
    private Outcome dateOrderOutcome(QualityRule rule, TableAnalysis analysis, double threshold) {
        String first = rule.getColumnName();
        String second = rule.getSecondColumnName();
        boolean secondExists = analysis.getColumnMetrics().stream().anyMatch(metric -> metric.getColumnName().equals(second));
        if (!secondExists) {
            return Outcome.of(false, 0, "Column " + second + " does not exist in this version");
        }
        CrossChecks.DateOrder order = CrossChecks.dateOrder(jsonService.toMap(analysis.getCrossChecksJson()), first, second);
        if (order == null) {
            return Outcome.skipped(first + " and " + second + " were not measured as dates, or no row has both filled");
        }
        double percent = order.compared() == 0 ? 0 : order.violations() * 100.0 / order.compared();
        String example = order.violations() > 0 && !order.examples().isEmpty()
            ? ", for example " + clip(String.valueOf(order.examples().get(0)), 100) : "";
        return Outcome.of(percent <= threshold, percent, clip(
            order.violations() + " of " + order.compared() + " row(s) have " + first + " after " + second
                + " (" + fmt(percent) + "%, limit " + fmt(threshold) + "%)" + example, 480));
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listValue(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    public static RuleSummary summarize(List<RuleResult> results) {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        int critical = 0;
        for (RuleResult result : results) {
            switch (result.getStatus()) {
                case PASS -> passed++;
                case FAIL -> {
                    failed++;
                    if (result.getSeverity() == RuleSeverity.CRITICAL) {
                        critical++;
                    }
                }
                case SKIPPED -> skipped++;
            }
        }
        return new RuleSummary(results.size(), passed, failed, skipped, critical);
    }

    private static RuleResults toResults(List<RuleResult> results) {
        return new RuleResults(
            summarize(results),
            results.stream()
                .map(result -> new RuleResultView(
                    result.getRuleId(),
                    result.getTableName(),
                    result.getColumnName(),
                    result.getType().name(),
                    result.getSeverity().name(),
                    result.getDescription(),
                    result.getStatus().name(),
                    result.getActual(),
                    result.getMessage(),
                    result.getEvaluatedAt()
                ))
                .toList()
        );
    }

    private static RuleView toView(QualityRule rule) {
        return new RuleView(
            rule.getId(),
            rule.getTableName(),
            rule.getColumnName(),
            rule.getType().name(),
            rule.getThreshold(),
            rule.getSeverity().name(),
            rule.isEnabled(),
            describe(rule),
            rule.getCreatedAt(),
            rule.getFormat(),
            rule.getSecondColumnName(),
            rule.getState().name(),
            rule.getOrigin().name(),
            rule.getRationale()
        );
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String label) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown " + label + ": " + value);
        }
    }

    /** 12 for whole numbers, 12.5 otherwise. */
    static String fmt(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private Dataset requireDataset(Long datasetId) {
        return datasetRepository.findById(datasetId).orElseThrow(() -> new NotFoundException("Dataset not found"));
    }

    private QualityRule requireRule(Long datasetId, Long ruleId) {
        return ruleRepository.findByIdAndDatasetId(ruleId, datasetId).orElseThrow(() -> new NotFoundException("Rule not found"));
    }

    private DatasetVersion requireVersion(Long datasetId, Long versionId) {
        return versionRepository.findById(versionId)
            .filter(version -> version.getDataset().getId().equals(datasetId))
            .orElseThrow(() -> new NotFoundException("Dataset version not found"));
    }
}
