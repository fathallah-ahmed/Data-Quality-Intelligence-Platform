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
