package wevioo.backend.service;

import org.springframework.stereotype.Service;
import wevioo.backend.domain.ChangeStatus;
import wevioo.backend.domain.ColumnMetric;
import wevioo.backend.domain.QualityRule;
import wevioo.backend.domain.RuleType;
import wevioo.backend.domain.TableAnalysis;
import wevioo.backend.domain.TableVersion;
import wevioo.backend.dto.DatasetDtos.QualityDimension;
import wevioo.backend.repository.QualityRuleRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The quality dimensions of a version, each with the figures it is made of (values that meet it out
 * of values that were checked) and the columns or rules it rests on. A dimension that cannot be
 * measured is reported as such, with the reason, instead of being given a flattering default.
 */
@Service
public class QualityDimensionsService {
    /** Column roles whose values have a format the pipeline measures: role -> format. */
    private static final Map<String, String> FORMAT_BY_ROLE = Map.of("EMAIL", "EMAIL", "PHONE", "PHONE", "URL", "URL", "DATE", "DATE");

    private final QualityRuleRepository ruleRepository;
    private final JsonService jsonService;

    public QualityDimensionsService(QualityRuleRepository ruleRepository, JsonService jsonService) {
        this.ruleRepository = ruleRepository;
        this.jsonService = jsonService;
    }

    public List<QualityDimension> forVersion(Long datasetId, List<TableVersion> tableVersions) {
        List<TableAnalysis> analyses = tableVersions.stream()
            .filter(table -> table.getChangeStatus() != ChangeStatus.REMOVED)
            .map(TableVersion::getAnalysis)
            .filter(analysis -> analysis != null && analysis.getRowCount() != null)
            .toList();
        List<QualityRule> dateRules = ruleRepository.findByDatasetIdOrderByIdAsc(datasetId).stream()
            .filter(QualityRule::isChecked)
            .filter(rule -> rule.getType() == RuleType.DATE_ORDER)
            .toList();
        return List.of(completeness(analyses), uniqueness(analyses), validity(analyses), consistency(tableVersions, dateRules));
    }

    private static QualityDimension completeness(List<TableAnalysis> analyses) {
        long cells = 0;
        long filled = 0;
        for (TableAnalysis analysis : analyses) {
            long rows = analysis.getRowCount();
            long columns = analysis.getColumnCount() == null ? 0 : analysis.getColumnCount();
            long missing = analysis.getMissingCount() == null ? 0 : analysis.getMissingCount();
            cells += rows * columns;
            filled += Math.max(0, rows * columns - missing);
        }
        return dimension("COMPLETENESS", "Completeness", filled, cells,
            "Filled cells out of all cells (rows × columns).",
            cells == 0 ? "No table has rows yet." : "All columns of " + analyses.size() + " table(s).");
    }

    private static QualityDimension uniqueness(List<TableAnalysis> analyses) {
        long rows = 0;
        long distinctRows = 0;
        for (TableAnalysis analysis : analyses) {
            long count = analysis.getRowCount();
            long duplicates = analysis.getDuplicateCount() == null ? 0 : analysis.getDuplicateCount();
            rows += count;
            distinctRows += Math.max(0, count - duplicates);
        }
        return dimension("UNIQUENESS", "Uniqueness", distinctRows, rows,
            "Rows that are not an exact copy of another row, out of all rows. A repeated identifier is not a duplicate.",
            rows == 0 ? "No table has rows yet." : "Whole rows of " + analyses.size() + " table(s).");
    }

    private QualityDimension validity(List<TableAnalysis> analyses) {
        long checked = 0;
        long valid = 0;
        List<String> columns = new ArrayList<>();
        for (TableAnalysis analysis : analyses) {
            for (ColumnMetric column : analysis.getColumnMetrics()) {
                // Versions analysed before roles existed have no role (Map.of rejects a null key).
                String format = column.getRole() == null ? null : FORMAT_BY_ROLE.get(column.getRole());
                if (format == null) {
                    continue;
                }
                Map<String, Object> measured = CrossChecks.mapValue(jsonService.toMap(column.getFormatStatsJson()).get(format));
                long count = CrossChecks.longValue(measured.get("checked"));
                if (count == 0) {
                    continue;
                }
                checked += count;
                valid += CrossChecks.longValue(measured.get("valid"));
                columns.add(analysis.getTableVersion().getTableName() + "." + column.getColumnName() + " (" + format.toLowerCase() + ")");
            }
        }
        String definition = "Values that follow the format of their column (email, phone, URL, date), out of the filled values of those columns.";
        if (checked == 0) {
            return new QualityDimension("VALIDITY", "Validity", null, null, null, definition,
                "Not measured: no column was recognised as an email, a phone number, a URL or a date.");
        }
        return dimension("VALIDITY", "Validity", valid, checked, definition, "Columns: " + String.join(", ", columns) + ".");
    }

    private QualityDimension consistency(List<TableVersion> tableVersions, List<QualityRule> dateRules) {
        String definition = "Rows where the approved date-order rules hold, out of the rows where both dates are filled.";
        Map<String, TableAnalysis> byTable = tableVersions.stream()
            .filter(table -> table.getChangeStatus() != ChangeStatus.REMOVED && table.getAnalysis() != null)
            .collect(Collectors.toMap(TableVersion::getTableName, TableVersion::getAnalysis, (first, second) -> first));
        long compared = 0;
        long consistent = 0;
        List<String> used = new ArrayList<>();
        for (QualityRule rule : dateRules) {
            TableAnalysis analysis = byTable.get(rule.getTableName());
            if (analysis == null) {
                continue;
            }
            CrossChecks.DateOrder order = CrossChecks.dateOrder(
                jsonService.toMap(analysis.getCrossChecksJson()), rule.getColumnName(), rule.getSecondColumnName());
            if (order == null || order.compared() == 0) {
                continue;
            }
            compared += order.compared();
            consistent += Math.max(0, order.compared() - order.violations());
            used.add(RuleService.describe(rule));
        }
        if (compared == 0) {
            return new QualityDimension("CONSISTENCY", "Consistency", null, null, null, definition,
                dateRules.isEmpty()
                    ? "Not measured: no date-order rule is approved. Add one in the Rules tab."
                    : "Not measured: no row has both dates filled.");
        }
        return dimension("CONSISTENCY", "Consistency", consistent, compared, definition, "Rules: " + String.join("; ", used) + ".");
    }

    private static QualityDimension dimension(String key, String label, long numerator, long denominator, String definition, String basis) {
        if (denominator == 0) {
            return new QualityDimension(key, label, null, null, null, definition, basis);
        }
        double percent = Math.max(0, Math.min(100, numerator * 100.0 / denominator));
        return new QualityDimension(key, label, percent, numerator, denominator, definition, basis);
    }
}
