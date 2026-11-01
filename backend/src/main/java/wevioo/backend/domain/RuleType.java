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
    MAX_ROW_DROP_PERCENT(false, true),
    /** At least `threshold` percent of the filled values of the column match the rule's format. */
    MIN_FORMAT_VALID_PERCENT(true, true),
    /** On rows where both dates are filled, the column is not after the second column in at most `threshold` percent. */
    DATE_ORDER(true, true);

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

    /** The rule names one of the {@link RuleFormat}s. */
    public boolean needsFormat() {
        return this == MIN_FORMAT_VALID_PERCENT;
    }

    /** The rule compares the column with another column. */
    public boolean needsSecondColumn() {
        return this == DATE_ORDER;
    }

    /** Percent thresholds are limited to 0..100. */
    public boolean isPercent() {
        return this == MAX_MISSING_PERCENT || this == MAX_DUPLICATE_PERCENT || this == MAX_ROW_DROP_PERCENT
            || this == MIN_FORMAT_VALID_PERCENT || this == DATE_ORDER;
    }
}
