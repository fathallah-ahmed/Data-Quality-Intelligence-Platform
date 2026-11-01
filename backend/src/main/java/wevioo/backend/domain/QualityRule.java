package wevioo.backend.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** A check the user wants every version of a dataset to satisfy. */
@Entity
@Table(name = "quality_rule")
public class QualityRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "dataset_id")
    private Dataset dataset;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "column_name")
    private String columnName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 40)
    private RuleType type;

    private Double threshold;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private RuleSeverity severity = RuleSeverity.WARNING;

    @Column(nullable = false)
    private boolean enabled = true;

    /** For a format rule: the {@link RuleFormat} name. */
    @Column(name = "format", length = 20)
    private String format;

    /** For a column comparison: the column the first one is compared with. */
    @Column(name = "second_column_name")
    private String secondColumnName;

    /** Nullable so rules saved before suggestions existed stay valid; no value means ACTIVE. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "state", length = 20)
    private RuleState state;

    /** Nullable for the same reason; no value means MANUAL. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "origin", length = 20)
    private RuleOrigin origin;

    /** Why the analysis proposed the rule, in terms of the user's goal. */
    @Column(length = 1000)
    private String rationale;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Dataset getDataset() { return dataset; }
    public void setDataset(Dataset dataset) { this.dataset = dataset; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }
    public RuleType getType() { return type; }
    public void setType(RuleType type) { this.type = type; }
    public Double getThreshold() { return threshold; }
    public void setThreshold(Double threshold) { this.threshold = threshold; }
    public RuleSeverity getSeverity() { return severity; }
    public void setSeverity(RuleSeverity severity) { this.severity = severity; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getSecondColumnName() { return secondColumnName; }
    public void setSecondColumnName(String secondColumnName) { this.secondColumnName = secondColumnName; }
    public RuleState getState() { return state == null ? RuleState.ACTIVE : state; }
    public void setState(RuleState state) { this.state = state; }
    public RuleOrigin getOrigin() { return origin == null ? RuleOrigin.MANUAL : origin; }
    public void setOrigin(RuleOrigin origin) { this.origin = origin; }
    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }
    public Instant getCreatedAt() { return createdAt; }

    /** True when the rule is approved and switched on: the only rules that are evaluated. */
    public boolean isChecked() { return enabled && getState() == RuleState.ACTIVE; }
}
