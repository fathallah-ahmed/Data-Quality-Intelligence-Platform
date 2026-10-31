package wevioo.backend.domain;

/** The checks a data-quality rule can make. */
public enum RuleType {
    /** The column has no missing values. */
    NOT_NULL(true, false),
    /** The column has at most `threshold` percent missing values. */
    MAX_MISSING_PERCENT(true, true),
    /** Every non-missing value of the column is distinct. */
    UNIQUE(true, false),
    /** The table has at least `threshold` rows. */
    MIN_ROWS(false, true),
    /** At most `threshold` percent of the rows are exact duplicates. */
    MAX_DUPLICATE_PERCENT(false, true),
    /** The table quality score is at least `threshold`. */
    MIN_QUALITY_SCORE(false, true),
    /** The table loses at most `threshold` percent of its rows compared with the previous version. */
    MAX_ROW_DROP_PERCENT(false, true);

    private final boolean needsColumn;
    private final boolean needsThreshold;

    RuleType(boolean needsColumn, boolean needsThreshold) {
        this.needsColumn = needsColumn;
        this.needsThreshold = needsThreshold;
    }

    public boolean needsColumn() {
        return needsColumn;
    }

    public boolean needsThreshold() {
        return needsThreshold;
    }

    /** Percent thresholds are limited to 0..100. */
    public boolean isPercent() {
        return this == MAX_MISSING_PERCENT || this == MAX_DUPLICATE_PERCENT || this == MAX_ROW_DROP_PERCENT;
    }
}
