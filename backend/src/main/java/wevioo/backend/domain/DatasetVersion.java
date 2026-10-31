package wevioo.backend.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
    name = "dataset_version",
    uniqueConstraints = @UniqueConstraint(columnNames = {"dataset_id", "version_number"})
)
public class DatasetVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "dataset_id")
    private Dataset dataset;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    @Column(name = "expected_table_count")
    private Integer expectedTableCount;

    @Lob
    @Column(name = "data_signature_json")
    private String dataSignatureJson;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private RunStatus status = RunStatus.PENDING;

    @OneToMany(mappedBy = "datasetVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TableVersion> tableVersions = new ArrayList<>();

    @OneToMany(mappedBy = "datasetVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PipelineRun> pipelineRuns = new ArrayList<>();

    @OneToOne(mappedBy = "datasetVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private GlobalAnalysis globalAnalysis;

    @OneToMany(mappedBy = "datasetVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RuleResult> ruleResults = new ArrayList<>();

    public Long getId() { return id; }
    public Dataset getDataset() { return dataset; }
    public void setDataset(Dataset dataset) { this.dataset = dataset; }
    public Integer getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Integer versionNumber) { this.versionNumber = versionNumber; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public Integer getExpectedTableCount() { return expectedTableCount; }
    public void setExpectedTableCount(Integer expectedTableCount) { this.expectedTableCount = expectedTableCount; }
    public String getDataSignatureJson() { return dataSignatureJson; }
    public void setDataSignatureJson(String dataSignatureJson) { this.dataSignatureJson = dataSignatureJson; }
    public Instant getUploadedAt() { return uploadedAt; }
    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }
    public List<TableVersion> getTableVersions() { return tableVersions; }
    public List<PipelineRun> getPipelineRuns() { return pipelineRuns; }
    public GlobalAnalysis getGlobalAnalysis() { return globalAnalysis; }
    public List<RuleResult> getRuleResults() { return ruleResults; }
    public void setGlobalAnalysis(GlobalAnalysis globalAnalysis) { this.globalAnalysis = globalAnalysis; }
}
