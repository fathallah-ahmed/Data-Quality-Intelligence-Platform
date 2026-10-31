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
    public Instant getCreatedAt() { return createdAt; }
}
