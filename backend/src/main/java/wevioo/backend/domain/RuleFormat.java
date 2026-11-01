package wevioo.backend.domain;

/** The formats the pipeline measures on text columns (see {@code format_profile.py}). */
public enum RuleFormat {
    EMAIL("an email address"),
    PHONE("a phone number"),
    URL("a web address"),
    DATE("a date"),
    NUMBER("a number");

    private final String article;

    RuleFormat(String article) {
        this.article = article;
    }

    /** How the format reads in a sentence, e.g. "an email address". */
    public String article() {
        return article;
    }
}
