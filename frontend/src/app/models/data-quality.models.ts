export interface VersionSummary {
  id: number;
  versionNumber: number;
  status: string;
  filePath: string;
  expectedTableCount?: number;
  uploadedAt: string;
  pipelineRunId?: string;
}

export interface DatasetSummary {
  id: number;
  name: string;
  sourceType: string;
  createdAt: string;
  updatedAt: string;
  latestVersion?: VersionSummary;
}

export interface UploadResponse {
  datasetId: number;
  versionId: number;
  versionNumber: number;
  pipelineRunId?: string;
  status: string;
  filePath: string;
  fileCount: number;
  airflowTriggered: boolean;
  airflowMessage: string;
  duplicateVersion: boolean;
  rerunStarted: boolean;
  duplicateMessage?: string;
}

export interface ColumnMetricView {
  columnName: string;
  dataType: string;
  missingCount: number;
  missingPercentage: number;
  uniqueCount: number;
  minValue?: number;
  maxValue?: number;
}

export interface TableAnalysisView {
  tableVersionId: number;
  tableName: string;
  changeStatus: string;
  rowCount?: number;
  columnCount?: number;
  missingCount?: number;
  duplicateCount?: number;
  qualityScore?: number;
  llmSummary?: string;
  llmInterpretation?: Record<string, unknown>;
  dataSignature?: Record<string, unknown>;
  rawMetrics?: Record<string, unknown>;
  rawAnalysis?: Record<string, unknown>;
  columns: ColumnMetricView[];
}

export interface GlobalAnalysisView {
  id: number;
  totalTables: number;
  totalRows: number;
  totalColumns: number;
  globalQualityScore?: number;
  llmGlobalSummary?: string;
  llmInterpretation?: Record<string, unknown>;
  recommendations?: string;
  rawGlobalAnalysis?: Record<string, unknown>;
}

export interface VersionDetails {
  version: VersionSummary;
  globalAnalysis?: GlobalAnalysisView;
  tables: TableAnalysisView[];
}

export interface ClassDiagramColumn {
  name: string;
  type: string;
  role: string;
}

export interface ClassDiagramEntity {
  tableName: string;
  rowCount: number;
  columns: ClassDiagramColumn[];
}

export interface ClassDiagramRelation {
  sourceTable: string;
  sourceColumn: string;
  targetTable: string;
  targetColumn: string;
  confidence: 'high' | 'medium';
  reason: string;
  coverage?: number;
  sourceDistinctCount?: number;
  targetDistinctCount?: number;
}

export interface DataModelField {
  name: string;
  type: string;
  role: string;
  y: number;
}

export interface DataModelNode {
  id: string;
  label: string;
  subtitle: string;
  x: number;
  y: number;
  width: number;
  height: number;
  row: number;
  column: number;
  fields: DataModelField[];
  moreCount: number;
}

export interface DataModelEdge {
  id: string;
  sourceTable: string;
  targetTable: string;
  label: string;
  labelWidth: number;
  confidence: string;
  reason: string;
  path: string;
  labelX: number;
  labelY: number;
  sourceCardinality: string;
  targetCardinality: string;
  sourceCardinalityX: number;
  sourceCardinalityY: number;
  targetCardinalityX: number;
  targetCardinalityY: number;
}

export interface DataModelMap {
  width: number;
  height: number;
  nodes: DataModelNode[];
  edges: DataModelEdge[];
}

export interface AuthMe {
  username: string;
  roles: string[];
}

export interface LiveEvent {
  type: string;
  datasetId?: number;
  versionId?: number;
  versionNumber?: number;
  pipelineRunId?: string;
  status?: string;
  tableName?: string;
  message?: string;
  emittedAt?: string;
}

export type ContextScope = 'none' | 'global' | 'per_table' | 'both';
