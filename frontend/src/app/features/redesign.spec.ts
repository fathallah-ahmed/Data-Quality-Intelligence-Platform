import { VersionDetails } from '../core/models';
import { qualityGrade, typeIcon } from '../shared/format';
import { ICONS } from '../shared/icon';
import { cleanLegacyText, insightText } from '../shared/insight';
import { tagTone } from './insights/insights-tab';
import { aiCoverage, columnsToReview, completeness, uniqueness } from './overview/version-metrics';

const details: VersionDetails = {
  version: { id: 1, versionNumber: 1, status: 'COMPLETED', filePath: '/x', uploadedAt: '2026-10-31T10:00:00Z' },
  tables: [
    {
      tableVersionId: 1, tableName: 'orders', changeStatus: 'NEW', rowCount: 10, columnCount: 2, missingCount: 2, duplicateCount: 1, llmStatus: 'ok',
      columns: [
        { columnName: 'id', dataType: 'int64', missingPercentage: 0 },
        { columnName: 'note', dataType: 'object', missingPercentage: 20 }
      ]
    },
    {
      tableVersionId: 2, tableName: 'gone', changeStatus: 'REMOVED', columns: []
    },
    {
      tableVersionId: 3, tableName: 'customers', changeStatus: 'NEW', rowCount: 10, columnCount: 3, missingCount: 0, duplicateCount: 0, llmStatus: 'unavailable',
      columns: [{ columnName: 'email', dataType: 'object', missingPercentage: 5 }]
    }
  ]
};

describe('redesign helpers', () => {
  it('grades quality scores', () => {
    expect(qualityGrade(95)).toEqual({ grade: 'A', label: 'Healthy' });
    expect(qualityGrade(80).grade).toBe('B');
    expect(qualityGrade(65).grade).toBe('C');
    expect(qualityGrade(10).grade).toBe('D');
    expect(qualityGrade(null).grade).toBe('–');
  });

  it('maps column types to icons that exist', () => {
    expect(typeIcon('Numerical')).toBe('hash');
    expect(typeIcon('Date')).toBe('calendar');
    expect(typeIcon('Categorical')).toBe('text');
    for (const label of ['Numerical', 'Date', 'Boolean', 'Text']) {
      expect(ICONS[typeIcon(label)]).toBeDefined();
    }
  });

  it('computes quality dimensions over ready tables only', () => {
    // 2 missing cells out of 10×2 + 10×3 = 50 cells.
    expect(completeness(details)).toBeCloseTo(96, 5);
    // 1 duplicate row out of 20 rows.
    expect(uniqueness(details)).toBeCloseTo(95, 5);
    expect(aiCoverage(details)).toBe(50);
  });

  it('lists the columns to review, worst first', () => {
    expect(columnsToReview(details)).toEqual([
      { table: 'orders', column: 'note', missing: 20 },
      { table: 'customers', column: 'email', missing: 5 }
    ]);
  });

  it('rewrites the legacy JSON type mix as words', () => {
    expect(cleanLegacyText('No duplicates. Type mix: {"TYPE_NUM": 2, "TYPE_TEXT": 6, "TYPE_CAT": 1}. Confirm keys.'))
      .toBe('No duplicates. Column types: 6 text, 2 numerical, 1 categorical. Confirm keys.');
    expect(insightText({ consistency: 'Type mix: {"TYPE_NUM": 3}.' }, 'consistency')).toBe('Column types: 3 numerical.');
    expect(cleanLegacyText('Nothing to change.')).toBe('Nothing to change.');
  });

  it('colours LLM severity tags', () => {
    expect(tagTone('High')).toBe('danger');
    expect(tagTone('not_ready')).toBe('danger');
    expect(tagTone('medium')).toBe('warning');
    expect(tagTone('Ready')).toBe('success');
    expect(tagTone('follow up')).toBeNull();
    expect(tagTone(undefined)).toBeNull();
  });
});
