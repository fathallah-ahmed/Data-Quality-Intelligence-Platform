export type RunStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED';
export type ChangeStatus = 'NEW' | 'MODIFIED' | 'UNCHANGED' | 'REMOVED' | 'UNKNOWN';
export type LlmStatus = 'ok' | 'unavailable' | 'invalid' | 'disabled' | 'pending';

export interface VersionSummary {
  id: number;
  versionNumber: number;
  status: RunStatus | string;
  filePath: string;
  expectedTableCount?: number | null;
  uploadedAt: string;
  pipelineRunId?: string | null;
  errorMessage?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
}

export interface DatasetSummary {
  id: number;
  name: string;
  sourceType: string;
  createdAt: string;
  updatedAt: string;
  versionCount?: number;
  /** Quality rules failing in the latest version. */
  failedRules?: number;
  latestVersion?: VersionSummary | null;
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
  duplicateMessage?: string | null;
  skippedFiles?: string[];
}

export interface ColumnMetricView {
  columnName: string;
  dataType: string;
  missingCount?: number | null;
  missingPercentage?: number | null;
  uniqueCount?: number | null;
  minValue?: number | null;
  maxValue?: number | null;
}

export interface TableAnalysisView {
  tableVersionId: number;
  tableName: string;
  changeStatus: ChangeStatus | string;
  rowCount?: number | null;
  columnCount?: number | null;
  missingCount?: number | null;
  duplicateCount?: number | null;
  qualityScore?: number | null;
  llmSummary?: string | null;
  llmStatus?: LlmStatus | string | null;
  llmModel?: string | null;
  llmInterpretation?: Record<string, unknown>;
  dataSignature?: Record<string, unknown>;
  rawMetrics?: Record<string, unknown>;
  rawAnalysis?: Record<string, unknown>;
  columns: ColumnMetricView[];
  /** Relationship candidates found by the pipeline from value overlap (snake_case keys). */
  relationships?: Record<string, unknown>[];
}

export interface GlobalAnalysisView {
  id: number;
  totalTables?: number | null;
  totalRows?: number | null;
  totalColumns?: number | null;
  llmGlobalSummary?: string | null;
  llmStatus?: LlmStatus | string | null;
  llmInterpretation?: Record<string, unknown>;
  recommendations?: string | null;
  rawGlobalAnalysis?: Record<string, unknown>;
}

export interface FailedTableView {
  tableName: string;
  error: string;
}

export interface VersionDetails {
  version: VersionSummary;
  globalAnalysis?: GlobalAnalysisView | null;
  tables: TableAnalysisView[];
  failedTables?: FailedTableView[];
  /** Row-weighted average of the table quality scores. */
  qualityScore?: number | null;
  /** Null until the quality rules have been evaluated for this version. */
  ruleSummary?: RuleSummary | null;
}

export type RuleType =
  | 'NOT_NULL'
  | 'MAX_MISSING_PERCENT'
  | 'UNIQUE'
  | 'MIN_ROWS'
  | 'MAX_DUPLICATE_PERCENT'
  | 'MIN_QUALITY_SCORE'
  | 'MAX_ROW_DROP_PERCENT'
  | 'MIN_FORMAT_VALID_PERCENT'
  | 'DATE_ORDER';
export type RuleFormat = 'EMAIL' | 'PHONE' | 'URL' | 'DATE' | 'NUMBER';
export type RuleSeverity = 'WARNING' | 'CRITICAL';
export type RuleStatus = 'PASS' | 'FAIL' | 'SKIPPED';
/** ACTIVE rules are checked; SUGGESTED ones wait for the user's decision. */
export type RuleState = 'ACTIVE' | 'SUGGESTED' | 'DISMISSED';

export interface RuleRequest {
  tableName: string;
  columnName?: string | null;
  type: RuleType;
  threshold?: number | null;
  severity: RuleSeverity;
  enabled?: boolean;
  /** MIN_FORMAT_VALID_PERCENT: the format the values must match. */
  format?: RuleFormat | null;
  /** DATE_ORDER: the column that the rule's column must not come after. */
  secondColumn?: string | null;
}

export interface RuleView {
  id: number;
  tableName: string;
  columnName?: string | null;
  type: RuleType;
  threshold?: number | null;
  severity: RuleSeverity;
  enabled: boolean;
  description: string;
  createdAt: string;
  format?: RuleFormat | null;
  secondColumn?: string | null;
  state: RuleState;
  /** AI when the analysis proposed the rule from the user's goal. */
  origin: 'MANUAL' | 'AI';
  /** Why the analysis proposed the rule. */
  rationale?: string | null;
}

export interface RuleResultView {
  ruleId: number;
  tableName: string;
  columnName?: string | null;
  type: RuleType;
  severity: RuleSeverity;
  description: string;
  status: RuleStatus;
  actual?: number | null;
  message?: string | null;
  evaluatedAt: string;
}

export interface RuleSummary {
  total: number;
  passed: number;
  failed: number;
  skipped: number;
  criticalFailed: number;
}

export interface RuleResults {
  summary: RuleSummary;
  results: RuleResultView[];
}

export interface DiffVersionRef {
  id: number;
  versionNumber: number;
  status: string;
  uploadedAt: string;
}

export interface TableDiff {
  tableName: string;
  change: 'ADDED' | 'REMOVED' | 'CHANGED' | 'UNCHANGED';
  rowsFrom?: number | null;
  rowsTo?: number | null;
  columnsFrom?: number | null;
  columnsTo?: number | null;
  scoreFrom?: number | null;
  scoreTo?: number | null;
  missingFrom?: number | null;
  missingTo?: number | null;
  duplicatesFrom?: number | null;
  duplicatesTo?: number | null;
  contentChanged: boolean;
  columnsAdded: string[];
  columnsRemoved: string[];
  typeChanges: { column: string; from?: string | null; to?: string | null }[];
  missingShifts: { column: string; from: number; to: number; delta: number }[];
}

export interface VersionDiff {
  from: DiffVersionRef;
  to: DiffVersionRef;
  scoreFrom?: number | null;
  scoreTo?: number | null;
  scoreDelta?: number | null;
  rowsFrom?: number | null;
  rowsTo?: number | null;
  tablesAdded: number;
  tablesRemoved: number;
  tablesChanged: number;
  tablesUnchanged: number;
  tables: TableDiff[];
}

export interface AuthMe {
  username: string;
  roles: string[];
}

export interface LoginResponse {
  token: string;
  expiresAt: string;
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

export interface UploadRequest {
  files: File[];
  name?: string;
  contextScope: ContextScope;
  globalDescription?: string;
  globalQuestion?: string;
  tableDescriptions?: Record<string, string>;
  /** What the data is about, what the user wants to check, and what one row stands for. */
  domain?: string;
  objective?: string;
  rowGrain?: string;
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
  /** Where the relation comes from: value overlap computed by the pipeline, or column-name inference. */
  origin: 'values' | 'names';
  coverage?: number;
  overlapCount?: number;
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
