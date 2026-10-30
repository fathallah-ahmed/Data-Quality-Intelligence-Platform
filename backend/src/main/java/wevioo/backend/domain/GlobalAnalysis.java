package wevioo.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "global_analysis")
public class GlobalAnalysis {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "dataset_version_id")
    private DatasetVersion datasetVersion;

    @Column(name = "total_tables")
    private Integer totalTables;

    @Column(name = "total_rows")
    private Long totalRows;

    @Column(name = "total_columns")
    private Long totalColumns;

    @Column(name = "global_quality_score")
    private Double globalQualityScore;

    @Lob
    @Column(name = "llm_global_summary")
    private String llmGlobalSummary;

    @Lob
    @Column(name = "llm_interpretation_json")
    private String llmInterpretationJson;

    @Lob
    @Column(name = "recommendations")
    private String recommendations;

    @Column(name = "analysis_json_path", length = 1000)
    private String analysisJsonPath;

    @Column(name = "llm_status", length = 32)
    private String llmStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public DatasetVersion getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(DatasetVersion datasetVersion) { this.datasetVersion = datasetVersion; }
    public Integer getTotalTables() { return totalTables; }
    public void setTotalTables(Integer totalTables) { this.totalTables = totalTables; }
    public Long getTotalRows() { return totalRows; }
    public void setTotalRows(Long totalRows) { this.totalRows = totalRows; }
    public Long getTotalColumns() { return totalColumns; }
    public void setTotalColumns(Long totalColumns) { this.totalColumns = totalColumns; }
    public Double getGlobalQualityScore() { return globalQualityScore; }
    public void setGlobalQualityScore(Double globalQualityScore) { this.globalQualityScore = globalQualityScore; }
    public String getLlmGlobalSummary() { return llmGlobalSummary; }
    public void setLlmGlobalSummary(String llmGlobalSummary) { this.llmGlobalSummary = llmGlobalSummary; }
    public String getLlmInterpretationJson() { return llmInterpretationJson; }
    public void setLlmInterpretationJson(String llmInterpretationJson) { this.llmInterpretationJson = llmInterpretationJson; }
    public String getRecommendations() { return recommendations; }
    public void setRecommendations(String recommendations) { this.recommendations = recommendations; }
    public String getAnalysisJsonPath() { return analysisJsonPath; }
    public void setAnalysisJsonPath(String analysisJsonPath) { this.analysisJsonPath = analysisJsonPath; }
    public String getLlmStatus() { return llmStatus; }
    public void setLlmStatus(String llmStatus) { this.llmStatus = llmStatus; }
    public Instant getCreatedAt() { return createdAt; }
}
