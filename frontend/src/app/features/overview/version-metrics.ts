import { TableAnalysisView, VersionDetails } from '../../core/models';
import { insightText } from '../../shared/insight';
import { formatNumber, formatPercent } from '../../shared/format';

/** Derived numbers about a version, shared by the overview and the progress stepper. */

export function presentTables(details: VersionDetails): TableAnalysisView[] {
  return details.tables.filter((table) => table.changeStatus !== 'REMOVED');
}

/** Tables whose metrics have arrived. */
export function readyTables(details: VersionDetails): TableAnalysisView[] {
  return presentTables(details).filter((table) => table.rowCount !== null && table.rowCount !== undefined);
}

export function expectedTables(details: VersionDetails): number {
  return details.version.expectedTableCount
    ?? details.globalAnalysis?.totalTables
    ?? presentTables(details).length;
}

export function totalRows(details: VersionDetails): number {
  return readyTables(details).reduce((sum, table) => sum + (table.rowCount ?? 0), 0);
}

export function totalColumns(details: VersionDetails): number {
  return readyTables(details).reduce((sum, table) => sum + (table.columnCount ?? 0), 0);
}

export function changeCounts(details: VersionDetails): { status: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const table of details.tables) {
    counts.set(table.changeStatus, (counts.get(table.changeStatus) ?? 0) + 1);
  }
  return ['NEW', 'MODIFIED', 'UNCHANGED', 'REMOVED', 'UNKNOWN']
    .filter((status) => counts.has(status))
    .map((status) => ({ status, count: counts.get(status) ?? 0 }));
}

function meaningful(text: string): boolean {
  const trimmed = text.trim();
  return trimmed.length > 0 && !trimmed.startsWith('Processed ');
}

export function businessView(details: VersionDetails): string {
  const direct = insightText(details.globalAnalysis?.llmInterpretation, 'portfolio_summary', 'business_view');
  if (meaningful(direct)) {
    return direct;
  }
  const tables = readyTables(details);
  const names = tables.map((table) => table.tableName).join(', ') || 'no tables yet';
  return `This version contains ${tables.length} table(s): ${names}. Together they provide ${formatNumber(totalRows(details))} rows and ${formatNumber(totalColumns(details))} columns.`;
}

export function qualityPosture(details: VersionDetails): string {
  const direct = insightText(details.globalAnalysis?.llmInterpretation, 'portfolio_summary', 'data_quality_posture');
  if (meaningful(direct)) {
    return direct;
  }
  const tables = readyTables(details);
  const missing = tables.reduce((sum, table) => sum + (table.missingCount ?? 0), 0);
  const duplicates = tables.reduce((sum, table) => sum + (table.duplicateCount ?? 0), 0);
  const highMissing = tables.flatMap((table) => table.columns
    .filter((column) => (column.missingPercentage ?? 0) >= 50)
    .map((column) => `${table.tableName}.${column.columnName} (${formatPercent(column.missingPercentage)})`));
  const parts = [
    missing > 0 ? `${formatNumber(missing)} missing cell(s)` : 'no missing cells',
    duplicates > 0 ? `${formatNumber(duplicates)} duplicate row(s)` : 'no duplicate rows'
  ];
  const risk = highMissing.length > 0 ? ` Columns to review: ${highMissing.slice(0, 5).join(', ')}.` : '';
  return `The quality check found ${parts.join(' and ')}.${risk}`;
}

export function finalAssessment(details: VersionDetails): string {
  const direct = insightText(details.globalAnalysis?.llmInterpretation, 'final_assessment');
  if (meaningful(direct)) {
    return direct;
  }
  const tables = readyTables(details);
  const highMissing = tables.reduce((count, table) => count + table.columns.filter((column) => (column.missingPercentage ?? 0) >= 50).length, 0);
  return highMissing > 0
    ? `Usable for exploration, but ${highMissing} column(s) are more than half empty and should be reviewed before reporting or model training.`
    : 'The tables look structurally consistent from the available metrics. Confirm key columns and business rules with the data owners.';
}

/** Share of filled cells across all ready tables (cell-weighted), 0–100. */
export function completeness(details: VersionDetails): number | null {
  const tables = readyTables(details);
  const cells = tables.reduce((sum, table) => sum + (table.rowCount ?? 0) * (table.columnCount ?? 0), 0);
  if (cells === 0) {
    return null;
  }
  const missing = tables.reduce((sum, table) => sum + (table.missingCount ?? 0), 0);
  return Math.max(0, 100 - (missing / cells) * 100);
}

/** Share of rows that are not exact duplicates, 0–100. */
export function uniqueness(details: VersionDetails): number | null {
  const rows = totalRows(details);
  if (rows === 0) {
    return null;
  }
  const duplicates = readyTables(details).reduce((sum, table) => sum + (table.duplicateCount ?? 0), 0);
  return Math.max(0, 100 - (duplicates / rows) * 100);
}

/** Share of tables whose insights come from a validated LLM answer, 0–100. */
export function aiCoverage(details: VersionDetails): number | null {
  const tables = readyTables(details).filter((table) => table.llmStatus && table.llmStatus !== 'pending' && table.llmStatus !== 'disabled');
  if (tables.length === 0) {
    return null;
  }
  return (tables.filter((table) => table.llmStatus === 'ok').length / tables.length) * 100;
}

export function tableMissingPercent(table: TableAnalysisView): number | null {
  const cells = (table.rowCount ?? 0) * (table.columnCount ?? 0);
  return cells > 0 ? ((table.missingCount ?? 0) / cells) * 100 : null;
}

/** Columns with the most missing values across the version, worst first. */
export function columnsToReview(details: VersionDetails, limit = 6): { table: string; column: string; missing: number }[] {
  return readyTables(details)
    .flatMap((table) => table.columns.map((column) => ({ table: table.tableName, column: column.columnName, missing: column.missingPercentage ?? 0 })))
    .filter((item) => item.missing > 0)
    .sort((a, b) => b.missing - a.missing)
    .slice(0, limit);
}
