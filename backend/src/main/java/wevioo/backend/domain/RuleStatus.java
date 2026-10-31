package wevioo.backend.domain;

public enum RuleStatus {
    PASS,
    FAIL,
    /** The rule could not be evaluated (table missing, no previous version, no rows...). */
    SKIPPED
}
