import { RuleResults, VersionDetails, VersionDiff } from '../core/models';
import { deltaInfo } from '../shared/format';
import { buildReport, reportFileName, reportMarkdown } from './report/report';
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
    expect(ruleKey({ type: 'MIN_ROWS', tableName: 't' })).toBe('MIN_ROWS|t|');
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

  it('reports a failed run prominently', () => {
    const failed: VersionDetails = { ...details, version: { ...details.version, status: 'FAILED', errorMessage: 'Airflow is not reachable' } };
    expect(reportMarkdown(buildReport({ datasetName: 'Shop', details: failed, rules: null, diff: null }))).toContain('> **The run finished with issues.** Airflow is not reachable');
  });
});
