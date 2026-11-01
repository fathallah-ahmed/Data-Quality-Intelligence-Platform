package wevioo.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "table_analysis")
public class TableAnalysis {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "table_version_id")
    private TableVersion tableVersion;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "column_count")
    private Integer columnCount;

    @Column(name = "missing_count")
    private Long missingCount;

    @Column(name = "duplicate_count")
    private Long duplicateCount;

    @Column(name = "quality_score")
    private Double qualityScore;

    @Column(name = "metrics_json_path", length = 1000)
    private String metricsJsonPath;

    @Column(name = "analysis_json_path", length = 1000)
    private String analysisJsonPath;

    @Lob
    @Column(name = "llm_summary")
    private String llmSummary;

    @Lob
    @Column(name = "llm_interpretation_json")
    private String llmInterpretationJson;

    @Column(name = "llm_model")
    private String llmModel;

    @Column(name = "llm_status", length = 32)
    private String llmStatus;

    @Lob
    @Column(name = "relationships_json")
    private String relationshipsJson;

    /** Checks that compare columns row by row, e.g. the order of two dates. */
    @Lob
    @Column(name = "cross_checks_json")
    private String crossChecksJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "tableAnalysis", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ColumnMetric> columnMetrics = new ArrayList<>();

    public Long getId() { return id; }
    public TableVersion getTableVersion() { return tableVersion; }
    public void setTableVersion(TableVersion tableVersion) { this.tableVersion = tableVersion; }
    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }
    public Integer getColumnCount() { return columnCount; }
    public void setColumnCount(Integer columnCount) { this.columnCount = columnCount; }
    public Long getMissingCount() { return missingCount; }
    public void setMissingCount(Long missingCount) { this.missingCount = missingCount; }
    public Long getDuplicateCount() { return duplicateCount; }
    public void setDuplicateCount(Long duplicateCount) { this.duplicateCount = duplicateCount; }
    public Double getQualityScore() { return qualityScore; }
    public void setQualityScore(Double qualityScore) { this.qualityScore = qualityScore; }
    public String getMetricsJsonPath() { return metricsJsonPath; }
    public void setMetricsJsonPath(String metricsJsonPath) { this.metricsJsonPath = metricsJsonPath; }
    public String getAnalysisJsonPath() { return analysisJsonPath; }
    public void setAnalysisJsonPath(String analysisJsonPath) { this.analysisJsonPath = analysisJsonPath; }
    public String getLlmSummary() { return llmSummary; }
    public void setLlmSummary(String llmSummary) { this.llmSummary = llmSummary; }
    public String getLlmInterpretationJson() { return llmInterpretationJson; }
    public void setLlmInterpretationJson(String llmInterpretationJson) { this.llmInterpretationJson = llmInterpretationJson; }
    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }
    public String getLlmStatus() { return llmStatus; }
    public void setLlmStatus(String llmStatus) { this.llmStatus = llmStatus; }
    public String getRelationshipsJson() { return relationshipsJson; }
    public void setRelationshipsJson(String relationshipsJson) { this.relationshipsJson = relationshipsJson; }
    public String getCrossChecksJson() { return crossChecksJson; }
    public void setCrossChecksJson(String crossChecksJson) { this.crossChecksJson = crossChecksJson; }
    public Instant getCreatedAt() { return createdAt; }
    public List<ColumnMetric> getColumnMetrics() { return columnMetrics; }
}
