package wevioo.backend.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "table_version")
public class TableVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "dataset_version_id")
    private DatasetVersion datasetVersion;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Lob
    @Column(name = "data_signature_json")
    private String dataSignatureJson;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "change_status", nullable = false, length = 32)
    private ChangeStatus changeStatus = ChangeStatus.UNKNOWN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_table_version_id")
    private TableVersion previousTableVersion;

    @OneToOne(mappedBy = "tableVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private TableAnalysis analysis;

    public Long getId() { return id; }
    public DatasetVersion getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(DatasetVersion datasetVersion) { this.datasetVersion = datasetVersion; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getDataSignatureJson() { return dataSignatureJson; }
    public void setDataSignatureJson(String dataSignatureJson) { this.dataSignatureJson = dataSignatureJson; }
    public ChangeStatus getChangeStatus() { return changeStatus; }
    public void setChangeStatus(ChangeStatus changeStatus) { this.changeStatus = changeStatus; }
    public TableVersion getPreviousTableVersion() { return previousTableVersion; }
    public void setPreviousTableVersion(TableVersion previousTableVersion) { this.previousTableVersion = previousTableVersion; }
    public TableAnalysis getAnalysis() { return analysis; }
    public void setAnalysis(TableAnalysis analysis) { this.analysis = analysis; }
}
