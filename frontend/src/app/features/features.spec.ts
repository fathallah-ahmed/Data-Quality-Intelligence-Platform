import { TableAnalysisView } from '../core/models';
import { buildDataModelMap, buildEntities, buildRelations } from './data-model/data-model';
import { associationPairs, columnProfiles, topValues } from './tables/table-profile';
import { mergeSelection } from './upload/upload-validation';

function table(name: string, columns: [string, string, number][], rows = 3, relationships: Record<string, unknown>[] = []): TableAnalysisView {
  return {
    tableVersionId: name.length,
    tableName: name,
    changeStatus: 'NEW',
    rowCount: rows,
    columnCount: columns.length,
    columns: columns.map(([columnName, dataType, uniqueCount]) => ({ columnName, dataType, uniqueCount, missingCount: 0, missingPercentage: 0 })),
    relationships
  };
}

describe('data model', () => {
  const customers = table('customers', [['customer_id', 'int64', 3], ['name', 'object', 3]]);
  const ordersWithRelation = table('orders', [['order_id', 'int64', 4], ['customer_id', 'int64', 3]], 4, [
    { source_table: 'orders', source_column: 'customer_id', target_table: 'customers', target_column: 'customer_id', confidence: 'high', coverage: 1, overlap_count: 3 }
  ]);

  it('uses the pipeline relationships first', () => {
    const relations = buildRelations([customers, ordersWithRelation]);
    expect(relations).toHaveLength(1);
    expect(relations[0]).toMatchObject({ sourceTable: 'orders', targetTable: 'customers', origin: 'values', coverage: 1 });
  });

  it('falls back to column-name inference', () => {
    const orders = table('orders', [['order_id', 'int64', 4], ['customer_id', 'int64', 3]], 4);
    const relations = buildRelations([customers, orders]);
    expect(relations[0]).toMatchObject({ sourceTable: 'orders', sourceColumn: 'customer_id', targetTable: 'customers', origin: 'names' });
  });

  it('marks keys and lays out every table', () => {
    const tables = [customers, ordersWithRelation];
    const relations = buildRelations(tables);
    const entities = buildEntities(tables, relations);
    expect(entities.find((entity) => entity.tableName === 'customers')?.columns[0].role).toBe('PK');
    expect(entities.find((entity) => entity.tableName === 'orders')?.columns[1].role).toBe('FK');
    const map = buildDataModelMap(tables, entities, relations);
    expect(map.nodes).toHaveLength(2);
    expect(map.edges).toHaveLength(1);
    expect(map.edges[0].path.startsWith('M ')).toBe(true);
  });
});

describe('table profile', () => {
  const summary = table('t', [['amount', 'float64', 3], ['city', 'object', 2]]);
  const detail: TableAnalysisView = {
    ...summary,
    rawAnalysis: { column_metrics: [{ name: 'amount', q1_value: 1, median_value: 2, q3_value: 3, mean_value: 2, std_value: 1 }] },
    rawMetrics: {
      features: { city: { detail: { frequent_values: [['Paris', { number: 2, perc: 66.7 }, null], ['Lyon', { number: 1, perc: 33.3 }, null]] } } },
      associations: { amount: { amount: 0, city: 0.8 }, city: { amount: 0.8, city: 0 } }
    }
  };

  it('merges summary metrics with the raw profile', () => {
    const [amount, city] = columnProfiles(summary, detail);
    expect(amount).toMatchObject({ type: 'Numerical', q1: 1, median: 2, q3: 3, std: 1 });
    expect(city.topValues[0]).toEqual({ value: 'Paris', count: 2, percent: 66.7 });
  });

  it('works before the raw profile is loaded', () => {
    expect(columnProfiles(summary, null)[0].q1).toBeNull();
  });

  it('reads associations from the nested matrix without duplicates or self-pairs', () => {
    expect(associationPairs(detail)).toEqual([{ left: 'amount', right: 'city', score: 0.8 }]);
    expect(associationPairs(null)).toEqual([]);
  });

  it('accepts object-shaped top values', () => {
    expect(topValues({ top_values: [{ value: 'x', count: 3 }] })).toEqual([{ value: 'x', count: 3, percent: null }]);
  });
});

describe('upload selection', () => {
  const csv = (name: string, content = 'a\n1\n') => new File([content], name, { type: 'text/csv' });

  it('adds files instead of replacing them, deduplicated by name', () => {
    const first = mergeSelection([], [csv('a.csv'), csv('b.csv')], 1000);
    const second = mergeSelection(first.files, [csv('B.csv', 'b\n2\n'), csv('c.csv')], 1000);
    expect(second.files.map((file) => file.name)).toEqual(['a.csv', 'B.csv', 'c.csv']);
  });

  it('rejects non-CSV, empty and oversized files with a reason', () => {
    const result = mergeSelection([], [csv('notes.txt'), csv('empty.csv', ''), csv('big.csv', 'x'.repeat(50)), csv('ok.csv')], 10);
    expect(result.files.map((file) => file.name)).toEqual(['ok.csv']);
    expect(result.problems.map((problem) => problem.name)).toEqual(['notes.txt', 'empty.csv', 'big.csv']);
    expect(result.problems[1].reason).toBe('the file is empty');
  });
});
