package wevioo.backend.service;

import wevioo.backend.dto.DatasetDtos.TableAnalysisView;

import java.util.List;

/** Version-level quality score shared by the API and the UI. */
public final class QualityScores {
    private QualityScores() {}

    /**
     * Row-weighted average of the table scores, so a large table counts more than a tiny lookup
     * table. Removed tables and tables without a score are ignored. When every scored table is
     * empty (0 rows) the plain average is used. Returns null when no table has a score yet.
     */
    public static Double rowWeighted(List<TableAnalysisView> tables) {
        double weightedSum = 0;
        double totalWeight = 0;
        double plainSum = 0;
        int scored = 0;
        for (TableAnalysisView table : tables) {
            if (table.qualityScore() == null || "REMOVED".equals(table.changeStatus())) {
                continue;
            }
            long rows = table.rowCount() == null ? 0 : Math.max(0, table.rowCount());
            weightedSum += table.qualityScore() * rows;
            totalWeight += rows;
            plainSum += table.qualityScore();
            scored++;
        }
        if (scored == 0) {
            return null;
        }
        return totalWeight > 0 ? weightedSum / totalWeight : plainSum / scored;
    }
}
