package wevioo.backend.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "column_metrics")
public class ColumnMetric {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "table_analysis_id")
    private TableAnalysis tableAnalysis;

    @Column(name = "column_name", nullable = false)
    private String columnName;

    @Column(name = "data_type")
    private String dataType;

    @Column(name = "missing_count")
    private Long missingCount;

    @Column(name = "missing_percentage")
    private Double missingPercentage;

    @Column(name = "unique_count")
    private Long uniqueCount;

    @Column(name = "min_value")
    private Double minValue;

    @Column(name = "max_value")
    private Double maxValue;

    /** Per format (EMAIL, PHONE...) how many values match, as measured by the pipeline; text columns only. */
    @Lob
    @Column(name = "format_stats_json")
    private String formatStatsJson;

    public Long getId() { return id; }
    public TableAnalysis getTableAnalysis() { return tableAnalysis; }
    public void setTableAnalysis(TableAnalysis tableAnalysis) { this.tableAnalysis = tableAnalysis; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public Long getMissingCount() { return missingCount; }
    public void setMissingCount(Long missingCount) { this.missingCount = missingCount; }
    public Double getMissingPercentage() { return missingPercentage; }
    public void setMissingPercentage(Double missingPercentage) { this.missingPercentage = missingPercentage; }
    public Long getUniqueCount() { return uniqueCount; }
    public void setUniqueCount(Long uniqueCount) { this.uniqueCount = uniqueCount; }
    public Double getMinValue() { return minValue; }
    public void setMinValue(Double minValue) { this.minValue = minValue; }
    public Double getMaxValue() { return maxValue; }
    public void setMaxValue(Double maxValue) { this.maxValue = maxValue; }
    public String getFormatStatsJson() { return formatStatsJson; }
    public void setFormatStatsJson(String formatStatsJson) { this.formatStatsJson = formatStatsJson; }
}
