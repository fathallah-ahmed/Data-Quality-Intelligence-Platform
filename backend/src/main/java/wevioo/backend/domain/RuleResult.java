package wevioo.backend.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * The outcome of one rule for one dataset version. The wording of the rule is copied here, so the
 * history stays readable after the rule is edited.
 */
@Entity
@Table(name = "rule_result")
public class RuleResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "dataset_version_id")
    private DatasetVersion datasetVersion;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "column_name")
    private String columnName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 40)
    private RuleType type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private RuleSeverity severity;

    @Column(nullable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private RuleStatus status;

    private Double actual;

    @Column(length = 500)
    private String message;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt = Instant.now();

    public Long getId() { return id; }
    public DatasetVersion getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(DatasetVersion datasetVersion) { this.datasetVersion = datasetVersion; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }
    public RuleType getType() { return type; }
    public void setType(RuleType type) { this.type = type; }
    public RuleSeverity getSeverity() { return severity; }
    public void setSeverity(RuleSeverity severity) { this.severity = severity; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public RuleStatus getStatus() { return status; }
    public void setStatus(RuleStatus status) { this.status = status; }
    public Double getActual() { return actual; }
    public void setActual(Double actual) { this.actual = actual; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(Instant evaluatedAt) { this.evaluatedAt = evaluatedAt; }
}
