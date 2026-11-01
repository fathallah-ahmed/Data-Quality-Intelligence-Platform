package wevioo.backend.domain;

/** Where a rule stands: checked on every version, waiting for the user's decision, or turned down. */
public enum RuleState {
    ACTIVE,
    /** Proposed by the analysis from the user's goal; not checked until the user approves it. */
    SUGGESTED,
    /** Turned down by the user; kept so the same suggestion does not come back. */
    DISMISSED
}
