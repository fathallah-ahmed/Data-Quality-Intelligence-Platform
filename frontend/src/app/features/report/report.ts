import { RuleResultView, RuleResults, VersionDetails, VersionDiff } from '../../core/models';
import { deltaInfo, formatNumber, formatPercent, formatScore, qualityGrade, statusLabel } from '../../shared/format';
import {
  aiCoverage,
  businessView,
  columnsToReview,
  expectedTables,
  finalAssessment,
  presentTables,
  qualityDimensions,
  qualityPosture,
  readyTables,
  SCORE_DEFINITION,
  tableMissingPercent,
  totalColumns,
  totalRows
} from '../overview/version-metrics';

export interface ReportInput {
  datasetName: string;
  details: VersionDetails;
  rules: RuleResults | null;
  diff: VersionDiff | null;
  generatedAt?: Date;
}

export interface ReportTableRow {
  name: string;
  score: string;
  rows: string;
  columns: string;
  missing: string;
  duplicates: string;
  change: string;
}

export interface ReportModel {
  title: string;
  datasetName: string;
  versionNumber: number;
  status: string;
  generatedAt: Date;
  score: string;
  grade: string;
  gradeLabel: string;
  stats: { label: string; value: string }[];
  dimensions: { label: string; value: string }[];
  /** What each score measures and what it is based on, so a number can be checked. */
  scoreDefinitions: { label: string; text: string }[];
  runIssue: string | null;
  changes: { heading: string; lines: string[] } | null;
  tables: ReportTableRow[];
  review: { column: string; missing: string }[];
  failedTables: { tableName: string; error: string }[];
  rules: { summary: string; failing: RuleResultView[] } | null;
  insights: { heading: string; text: string }[];
  notes: { table: string; text: string }[];
}

export function buildReport(input: ReportInput): ReportModel {
  const { details, datasetName, diff, rules } = input;
  const version = details.version;
  const grade = qualityGrade(details.qualityScore);
  const ready = readyTables(details);
  const status = version.status.toUpperCase();

  return {
    title: `Data quality report: ${datasetName}`,
    datasetName,
    versionNumber: version.versionNumber,
    status: statusLabel(status),
    generatedAt: input.generatedAt ?? new Date(),
    score: formatScore(details.qualityScore),
    grade: grade.grade,
    gradeLabel: grade.label,
    stats: [
      { label: 'Tables', value: `${ready.length} of ${Math.max(expectedTables(details), ready.length)}` },
      { label: 'Rows', value: formatNumber(totalRows(details)) },
      { label: 'Columns', value: formatNumber(totalColumns(details)) },
      { label: 'Missing cells', value: formatNumber(ready.reduce((sum, table) => sum + (table.missingCount ?? 0), 0)) }
    ],
    dimensions: [
      ...qualityDimensions(details).map((dimension) => ({
        label: dimension.label,
        value: dimension.value === null
          ? 'not measured'
          : dimension.detail ? `${formatPercent(dimension.value)} (${dimension.detail})` : formatPercent(dimension.value)
      })),
      { label: 'AI coverage', value: formatPercent(aiCoverage(details)) }
    ],
    scoreDefinitions: [
      { label: 'Quality score', text: SCORE_DEFINITION },
      ...(details.dimensions ?? []).map((dimension) => ({ label: dimension.label, text: `${dimension.definition} ${dimension.basis}` })),
      { label: 'AI coverage', text: 'Share of tables whose insights come from a validated LLM answer.' }
    ],
    runIssue: (status === 'FAILED' || status === 'PARTIAL') && version.errorMessage ? version.errorMessage : null,
    changes: diff ? changeSection(diff) : null,
    tables: presentTables(details).map((table) => ({
      name: table.tableName,
      score: formatScore(table.qualityScore),
      rows: formatNumber(table.rowCount),
      columns: formatNumber(table.columnCount),
      missing: formatPercent(tableMissingPercent(table)),
      duplicates: formatNumber(table.duplicateCount),
      change: statusLabel(table.changeStatus)
    })),
    review: columnsToReview(details, 10).map((item) => ({ column: `${item.table}.${item.column}`, missing: formatPercent(item.missing) })),
    failedTables: (details.failedTables ?? []).map((item) => ({ tableName: item.tableName, error: item.error })),
    rules: rules && rules.summary.total > 0 ? ruleSection(rules) : null,
    insights: [
      { heading: 'Business view', text: businessView(details) },
      { heading: 'Quality posture', text: qualityPosture(details) },
      { heading: 'Final assessment', text: finalAssessment(details) }
    ],
    notes: ready
      .filter((table) => !!table.llmSummary)
      .map((table) => ({ table: table.tableName, text: table.llmSummary ?? '' }))
  };
}

function ruleSection(rules: RuleResults): { summary: string; failing: RuleResultView[] } {
  const { summary } = rules;
  const checked = summary.passed + summary.failed;
  const text = summary.failed === 0
    ? `All ${checked} checked rule(s) pass.`
    : `${summary.passed} of ${checked} checked rule(s) pass; ${summary.failed} fail${summary.criticalFailed ? ` (${summary.criticalFailed} critical)` : ''}.`;
  return {
    summary: summary.skipped > 0 ? `${text} ${summary.skipped} could not be checked.` : text,
    failing: rules.results.filter((result) => result.status === 'FAIL')
  };
}

function changeSection(diff: VersionDiff): { heading: string; lines: string[] } {
  const lines: string[] = [];
  const score = deltaInfo(diff.scoreFrom, diff.scoreTo, 'higher-is-better', 1);
  if (diff.scoreFrom !== null && diff.scoreFrom !== undefined && diff.scoreTo !== null && diff.scoreTo !== undefined) {
    lines.push(`Quality score ${formatScore(diff.scoreFrom)} → ${formatScore(diff.scoreTo)} (${score.text})`);
  }
  const rows = deltaInfo(diff.rowsFrom, diff.rowsTo);
  lines.push(`Rows ${formatNumber(diff.rowsFrom)} → ${formatNumber(diff.rowsTo)} (${rows.text})`);
  lines.push(`Tables: ${diff.tablesChanged} changed, ${diff.tablesAdded} added, ${diff.tablesRemoved} removed, ${diff.tablesUnchanged} unchanged`);
  for (const table of diff.tables.filter((item) => item.change !== 'UNCHANGED')) {
    if (table.change === 'ADDED') {
      lines.push(`${table.tableName}: new table (${formatNumber(table.rowsTo)} rows)`);
    } else if (table.change === 'REMOVED') {
      lines.push(`${table.tableName}: removed (was ${formatNumber(table.rowsFrom)} rows)`);
    } else {
      const parts = [
        `rows ${formatNumber(table.rowsFrom)} → ${formatNumber(table.rowsTo)} (${deltaInfo(table.rowsFrom, table.rowsTo).text})`,
        table.scoreFrom !== null && table.scoreFrom !== undefined
          ? `score ${formatScore(table.scoreFrom)} → ${formatScore(table.scoreTo)} (${deltaInfo(table.scoreFrom, table.scoreTo, 'higher-is-better', 1).text})`
          : ''
      ].filter(Boolean);
      if (table.columnsAdded.length) {
        parts.push(`new columns: ${table.columnsAdded.join(', ')}`);
      }
      if (table.columnsRemoved.length) {
        parts.push(`removed columns: ${table.columnsRemoved.join(', ')}`);
      }
      if (table.typeChanges.length) {
        parts.push(`type changes: ${table.typeChanges.map((change) => `${change.column} ${change.from} → ${change.to}`).join(', ')}`);
      }
      lines.push(`${table.tableName}: ${parts.join('; ')}`);
    }
  }
  return { heading: `Changes since version ${diff.from.versionNumber}`, lines };
}

function cell(value: string): string {
  return value.replace(/\|/g, '\\|').replace(/\r?\n/g, ' ');
}

/** The report as Markdown, for wikis, tickets and e-mails. */
export function reportMarkdown(report: ReportModel): string {
  const out: string[] = [];
  out.push(`# ${report.title}`, '');
  out.push(`Version ${report.versionNumber} · ${report.status} · generated ${report.generatedAt.toLocaleString()}`, '');

  out.push('## Summary', '');
  out.push(`- **Quality score:** ${report.score} / 100, grade ${report.grade} (${report.gradeLabel})`);
  out.push(`- ${report.stats.map((stat) => `**${stat.label}:** ${stat.value}`).join(' · ')}`);
  out.push(`- ${report.dimensions.map((dimension) => `**${dimension.label}:** ${dimension.value}`).join(' · ')}`);
  if (report.runIssue) {
    out.push('', `> **The run finished with issues.** ${report.runIssue}`);
  }
  out.push('');

  out.push('## How the scores are computed', '');
  report.scoreDefinitions.forEach((definition) => out.push(`- **${definition.label}:** ${definition.text}`));
  out.push('');

  if (report.changes) {
    out.push(`## ${report.changes.heading}`, '');
    report.changes.lines.forEach((line) => out.push(`- ${line}`));
    out.push('');
  }

  out.push('## Table health', '');
  out.push('| Table | Score | Rows | Columns | Missing | Duplicates | Change |');
  out.push('| --- | ---: | ---: | ---: | ---: | ---: | --- |');
  report.tables.forEach((table) => out.push(
    `| ${cell(table.name)} | ${table.score} | ${table.rows} | ${table.columns} | ${table.missing} | ${table.duplicates} | ${table.change} |`
  ));
  out.push('');

  if (report.failedTables.length > 0) {
    out.push('## Tables that could not be analysed', '');
    report.failedTables.forEach((item) => out.push(`- \`${item.tableName}\`: ${item.error}`));
    out.push('');
  }

  if (report.review.length > 0) {
    out.push('## Columns to review', '');
    report.review.forEach((item) => out.push(`- \`${item.column}\`: ${item.missing} missing`));
    out.push('');
  }

  if (report.rules) {
    out.push('## Quality rules', '', report.rules.summary, '');
    report.rules.failing.forEach((result) => out.push(
      `- **${result.severity.toLowerCase()}** ${result.description}${result.message ? `: ${result.message}` : ''}`
    ));
    if (report.rules.failing.length > 0) {
      out.push('');
    }
  }

  out.push('## Insights', '');
  report.insights.forEach((insight) => out.push(`**${insight.heading}.** ${insight.text}`, ''));

  if (report.notes.length > 0) {
    out.push('## Table notes', '');
    report.notes.forEach((note) => out.push(`**${note.table}.** ${note.text}`, ''));
  }

  return out.join('\n').replace(/\n{3,}/g, '\n\n').trimEnd() + '\n';
}

export function reportFileName(report: ReportModel, extension = 'md'): string {
  const slug = report.datasetName.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'dataset';
  return `${slug}-v${report.versionNumber}-quality-report.${extension}`;
}
