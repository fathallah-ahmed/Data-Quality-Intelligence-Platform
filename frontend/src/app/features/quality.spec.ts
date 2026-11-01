import { RuleResults, VersionDetails, VersionDiff } from '../core/models';
import { deltaInfo } from '../shared/format';
import { browseQuery, sortFindings } from './graph/graph-tab';
import { qualityDimensions } from './overview/version-metrics';
import { buildReport, reportFileName, reportMarkdown } from './report/report';
import { roleExplanation } from './tables/table-profile';
import { describeRule, ruleKey, suggestRules } from './rules/rule-types';

function column(columnName: string, uniqueCount: number, missingCount = 0, missingPercentage = 0) {
  return { columnName, dataType: 'int64', uniqueCount, missingCount, missingPercentage };
}

const details: VersionDetails = {
  version: { id: 7, versionNumber: 2, status: 'COMPLETED', filePath: '/x', uploadedAt: '2026-10-30T10:00:00Z' },
  qualityScore: 93.3,
  ruleSummary: { total: 2, passed: 1, failed: 1, skipped: 0, criticalFailed: 1 },
  tables: [
    {
      tableVersionId: 1, tableName: 'orders', changeStatus: 'MODIFIED', rowCount: 100, columnCount: 3, missingCount: 20, duplicateCount: 0,
      qualityScore: 93.3, llmStatus: 'ok', llmSummary: 'Orders look healthy.',
      columns: [column('order_id', 100), column('customer_id', 80), column('note', 60, 20, 20)]
    },
    { tableVersionId: 2, tableName: 'gone', changeStatus: 'REMOVED', columns: [] }
  ]
};

describe('delta formatting', () => {
  it('words and colours a change by what the number means', () => {
    expect(deltaInfo(10, 8)).toMatchObject({ text: '−2', tone: 'info', direction: 'down', percent: -20 });
    expect(deltaInfo(90, 95.5, 'higher-is-better', 1)).toMatchObject({ text: '+5.5', tone: 'success', direction: 'up' });
    expect(deltaInfo(90, 85, 'higher-is-better')).toMatchObject({ tone: 'danger' });
    expect(deltaInfo(5, 9, 'lower-is-better')).toMatchObject({ text: '+4', tone: 'danger' });
    expect(deltaInfo(9, 5, 'lower-is-better')).toMatchObject({ tone: 'success' });
  });

  it('reports no change and missing values', () => {
    expect(deltaInfo(3, 3)).toMatchObject({ text: 'no change', direction: 'flat' });
    expect(deltaInfo(1.04, 1.01, 'higher-is-better', 1).text).toBe('no change');
    expect(deltaInfo(null, 3).text).toBe('–');
    expect(deltaInfo(0, 4).percent).toBeNull();
  });
});

describe('rule suggestions', () => {
  it('derives rules from how the data looks, keys first', () => {
    const suggestions = suggestRules(details, []);
    const sentences = suggestions.map(describeRule);
    expect(sentences.slice(0, 2)).toEqual(['orders.order_id is unique', 'orders.order_id has no missing values']);
    expect(suggestions[0].severity).toBe('CRITICAL');
    expect(sentences).toContain('orders.customer_id has no missing values');
    expect(sentences).toContain('orders scores at least 85');
    expect(sentences).toContain('orders has at least 50 rows');
    expect(sentences).toContain('orders loses at most 20% of its rows between versions');
    expect(sentences).toContain('orders.note has at most 25% missing values');
    expect(sentences.some((sentence) => sentence.startsWith('gone'))).toBe(false);
  });

  it('leaves out rules that already exist and does not suggest a row drop for a new table', () => {
    const existing = [{ type: 'UNIQUE' as const, tableName: 'orders', columnName: 'order_id' }];
    expect(suggestRules(details, existing).map(describeRule)).not.toContain('orders.order_id is unique');
    const fresh = { ...details, tables: [{ ...details.tables[0], changeStatus: 'NEW' }] };
    expect(suggestRules(fresh, []).map(describeRule).some((sentence) => sentence.includes('loses at most'))).toBe(false);
  });

  it('describes rules like the backend does', () => {
    expect(describeRule({ type: 'MAX_DUPLICATE_PERCENT', tableName: 't', threshold: 2.5 })).toBe('t has at most 2.5% duplicate rows');
    expect(ruleKey({ type: 'MIN_ROWS', tableName: 't' })).toBe('MIN_ROWS|t|||');
  });

  it('describes format and date-order rules and tells them apart by key', () => {
    expect(describeRule({ type: 'MIN_FORMAT_VALID_PERCENT', tableName: 'c', columnName: 'email', threshold: 99, format: 'EMAIL' }))
      .toBe('c.email has at least 99% of its values as an email address');
    expect(describeRule({ type: 'DATE_ORDER', tableName: 'a', columnName: 'opened', threshold: 0, secondColumn: 'closed' }))
      .toBe('a.opened is not after a.closed');
    expect(describeRule({ type: 'DATE_ORDER', tableName: 'a', columnName: 'opened', threshold: 2.5, secondColumn: 'closed' }))
      .toBe('a.opened is not after a.closed (up to 2.5% of rows may break this)');
    expect(ruleKey({ type: 'MIN_FORMAT_VALID_PERCENT', tableName: 'c', columnName: 'x', format: 'EMAIL' }))
      .not.toBe(ruleKey({ type: 'MIN_FORMAT_VALID_PERCENT', tableName: 'c', columnName: 'x', format: 'PHONE' }));
  });
});

describe('graph', () => {
  it('puts conflicts before warnings before notes, without changing the list it is given', () => {
    const findings = [
      { severity: 'info', kind: 'BUILT', message: 'built', examples: [] },
      { severity: 'warning', kind: 'BLANK_KEY', message: 'blank', examples: [] },
      { severity: 'conflict', kind: 'DEPENDENCY', message: 'conflict', examples: [] }
    ];

    expect(sortFindings(findings).map((finding) => finding.kind)).toEqual(['DEPENDENCY', 'BLANK_KEY', 'BUILT']);
    expect(findings[0].kind).toBe('BUILT');
  });

  it('gives the query that shows what a build wrote, limited to its own version', () => {
    expect(browseQuery('d150v155')).toBe("MATCH (n {_scope: 'd150v155'}) OPTIONAL MATCH (n)-[r]->(m) RETURN n, r, m LIMIT 200");
  });
});

describe('column roles and dimensions', () => {
  it('explains a role with its confidence, who decided and the clues', () => {
    expect(roleExplanation({
      columnName: 'email', dataType: 'object', role: 'EMAIL', roleConfidence: 1, roleSource: 'rules',
      roleEvidence: ["the name contains 'email'", '95% of its values look like email values']
    })).toBe("email, 100% sure (from the name, values and formats): the name contains 'email'; 95% of its values look like email values");
    expect(roleExplanation({ columnName: 'zip', dataType: 'object', role: 'POSTAL_CODE', roleConfidence: 0.6, roleSource: 'llm', roleEvidence: [] }))
      .toBe('postal code, 60% sure (chosen by the model)');
    expect(roleExplanation({ columnName: 'old', dataType: 'object' })).toBe('');
  });

  it('turns the backend dimensions into rows with their figures, or says they were not measured', () => {
    const rows = qualityDimensions({
      ...details,
      dimensions: [
        { key: 'COMPLETENESS', label: 'Completeness', percent: 93.3, numerator: 280, denominator: 300, definition: 'D.', basis: 'B.' },
        { key: 'CONSISTENCY', label: 'Consistency', percent: null, numerator: null, denominator: null, definition: 'D2.', basis: 'Not measured.' }
      ]
    });

    expect(rows[0]).toEqual({ label: 'Completeness', value: 93.3, detail: '280 of 300', hint: 'D. B.' });
    expect(rows[1]).toEqual({ label: 'Consistency', value: null, detail: 'not measured', hint: 'D2. Not measured.' });
  });

  it('falls back to completeness and uniqueness for a version without dimensions', () => {
    expect(qualityDimensions(details).map((row) => row.label)).toEqual(['Completeness', 'Uniqueness']);
  });
});

describe('report', () => {
  const rules: RuleResults = {
    summary: { total: 2, passed: 1, failed: 1, skipped: 0, criticalFailed: 1 },
    results: [
      { ruleId: 1, tableName: 'orders', type: 'MIN_ROWS', severity: 'CRITICAL', description: 'orders has at least 500 rows', status: 'FAIL', message: '100 row(s) (minimum 500)', evaluatedAt: '' },
      { ruleId: 2, tableName: 'orders', columnName: 'order_id', type: 'UNIQUE', severity: 'WARNING', description: 'orders.order_id is unique', status: 'PASS', evaluatedAt: '' }
    ]
  };
  const diff: VersionDiff = {
    from: { id: 6, versionNumber: 1, status: 'COMPLETED', uploadedAt: '' },
    to: { id: 7, versionNumber: 2, status: 'COMPLETED', uploadedAt: '' },
    scoreFrom: 100, scoreTo: 93.3, scoreDelta: -6.7, rowsFrom: 120, rowsTo: 100,
    tablesAdded: 0, tablesRemoved: 0, tablesChanged: 1, tablesUnchanged: 0,
    tables: [{
      tableName: 'orders', change: 'CHANGED', rowsFrom: 120, rowsTo: 100, columnsFrom: 2, columnsTo: 3, scoreFrom: 100, scoreTo: 93.3,
      contentChanged: true, columnsAdded: ['note'], columnsRemoved: [], typeChanges: [], missingShifts: []
    }]
  };

  it('writes a Markdown report with every section', () => {
    const markdown = reportMarkdown(buildReport({ datasetName: 'Shop', details, rules, diff, generatedAt: new Date('2026-10-31T12:00:00Z') }));
    expect(markdown).toContain('# Data quality report: Shop');
    expect(markdown).toContain('**Quality score:** 93.3 / 100, grade A (Healthy)');
    expect(markdown).toContain('## Changes since version 1');
    expect(markdown).toContain('Quality score 100.0 → 93.3 (−6.7)');
    expect(markdown).toContain('orders: rows 120 → 100 (−20); score 100.0 → 93.3 (−6.7); new columns: note');
    expect(markdown).toContain('| orders | 93.3 | 100 | 3 | 6.7% | 0 | Modified |');
    expect(markdown).not.toContain('| gone |');
    expect(markdown).toContain('- `orders.note`: 20.0% missing');
    expect(markdown).toContain('1 of 2 checked rule(s) pass; 1 fail (1 critical).');
    expect(markdown).toContain('- **critical** orders has at least 500 rows: 100 row(s) (minimum 500)');
    expect(markdown).toContain('**orders.** Orders look healthy.');
    expect(markdown.endsWith('\n')).toBe(true);
  });

  it('leaves out sections without data and names the file after the dataset', () => {
    const report = buildReport({ datasetName: 'Shop Data (2026)', details: { ...details, ruleSummary: null }, rules: null, diff: null });
    const markdown = reportMarkdown(report);
    expect(markdown).not.toContain('## Changes since');
    expect(markdown).not.toContain('## Quality rules');
    expect(reportFileName(report)).toBe('shop-data-2026-v2-quality-report.md');
  });

  it('shows each dimension with its figures and explains how every score is computed', () => {
    const measured: VersionDetails = {
      ...details,
      dimensions: [
        { key: 'COMPLETENESS', label: 'Completeness', percent: 93.3, numerator: 280, denominator: 300, definition: 'Filled cells out of all cells.', basis: 'All columns of 1 table(s).' },
        { key: 'VALIDITY', label: 'Validity', percent: null, numerator: null, denominator: null, definition: 'Values that follow their format.', basis: 'Not measured: no column was recognised as an email.' }
      ]
    };
    const markdown = reportMarkdown(buildReport({ datasetName: 'Shop', details: measured, rules: null, diff: null }));

    expect(markdown).toContain('**Completeness:** 93.3% (280 of 300)');
    expect(markdown).toContain('**Validity:** not measured');
    expect(markdown).toContain('## How the scores are computed');
    expect(markdown).toContain('- **Validity:** Values that follow their format. Not measured: no column was recognised as an email.');
    expect(markdown).toContain('- **Quality score:** Each table scores 100 minus');
  });

  it('reports a failed run prominently', () => {
    const failed: VersionDetails = { ...details, version: { ...details.version, status: 'FAILED', errorMessage: 'Airflow is not reachable' } };
    expect(reportMarkdown(buildReport({ datasetName: 'Shop', details: failed, rules: null, diff: null }))).toContain('> **The run finished with issues.** Airflow is not reachable');
  });
});
