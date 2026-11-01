package wevioo.backend.domain;

/** Who wrote a rule. */
public enum RuleOrigin {
    MANUAL,
    /** Proposed by the analysis from the goal the user gave at upload. */
    AI
}
