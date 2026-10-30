import { TableAnalysisView } from '../../core/models';
import { asRecord, asRecordArray } from '../../shared/insight';
import { isNumericType, typeLabel } from '../../shared/format';

/** One row of the merged column table (summary metrics + raw profile of the selected table). */
export interface ColumnProfile {
  name: string;
  dtype: string;
  type: string;
  numeric: boolean;
  missingCount: number | null;
  missingPercent: number | null;
  distinct: number | null;
  distinctPercent: number | null;
  min: number | null;
  q1: number | null;
  median: number | null;
  q3: number | null;
  max: number | null;
  mean: number | null;
  std: number | null;
  idLike: boolean;
  topValues: TopValue[];
}

export interface TopValue {
  value: string;
  count: number | null;
  percent: number | null;
}

export interface AssociationPair {
  left: string;
  right: string;
  score: number;
}

function num(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/**
 * Merge the always-available column metrics with the raw analysis / Sweetviz features
 * (quartiles, mean, std, top values) that are only loaded for the selected table.
 */
export function columnProfiles(table: TableAnalysisView, detail: TableAnalysisView | null): ColumnProfile[] {
  const rawColumns = new Map(asRecordArray(detail?.rawAnalysis?.['column_metrics']).map((column) => [String(column['name']), column]));
  const features = asRecord(detail?.rawMetrics?.['features']);
  const rows = table.rowCount ?? 0;
  return table.columns.map((column) => {
    const raw = rawColumns.get(column.columnName) ?? {};
    const feature = asRecord(features[column.columnName]);
    const stats = asRecord(feature['stats']);
    const distinct = column.uniqueCount ?? num(raw['distinct_count']);
    return {
      name: column.columnName,
      dtype: column.dataType,
      type: typeLabel(column.dataType),
      numeric: isNumericType(column.dataType),
      missingCount: column.missingCount ?? null,
      missingPercent: column.missingPercentage ?? null,
      distinct,
      distinctPercent: num(raw['distinct_percent']) ?? (distinct !== null && rows > 0 ? (distinct / rows) * 100 : null),
      min: column.minValue ?? num(raw['min_value']),
      q1: num(raw['q1_value']) ?? num(stats['perc25']),
      median: num(raw['median_value']) ?? num(stats['perc50']),
      q3: num(raw['q3_value']) ?? num(stats['perc75']),
      max: column.maxValue ?? num(raw['max_value']),
      mean: num(raw['mean_value']) ?? num(stats['mean']),
      std: num(raw['std_value']) ?? num(stats['std']),
      idLike: raw['is_id_like'] === true,
      topValues: topValues(feature)
    };
  });
}

/**
 * Sweetviz stores value counts as `detail.frequent_values` rows (`[value, {number, perc}, ...]`)
 * for high-cardinality columns and as `detail.full_count` objects (`{name, count: {number, perc}}`)
 * for low-cardinality ones.
 */
export function topValues(feature: Record<string, unknown>, limit = 8): TopValue[] {
  const detail = asRecord(feature['detail']);
  const values = detail['frequent_values'] ?? detail['full_count'] ?? feature['top_values'];
  if (!Array.isArray(values)) {
    return [];
  }
  // full_count ends with a synthetic "ALL" total row.
  const rows = values.filter((entry) => !(entry && typeof entry === 'object' && !Array.isArray(entry) && asRecord(entry)['is_total']));
  return rows.slice(0, limit).map((entry): TopValue => {
    if (Array.isArray(entry)) {
      const stats = asRecord(entry[1]);
      return { value: String(entry[0] ?? '∅'), count: num(stats['number']), percent: num(stats['perc']) };
    }
    const record = asRecord(entry);
    const count = asRecord(record['count']);
    return {
      value: String(record['name'] ?? record['value'] ?? '∅'),
      count: num(count['number']) ?? num(record['count']) ?? num(record['number']),
      percent: num(count['perc']) ?? num(record['percent']) ?? num(record['perc'])
    };
  });
}

/** Strongest pairwise associations from Sweetviz's nested `{a: {b: score}}` matrix. */
export function associationPairs(detail: TableAnalysisView | null, limit = 12): AssociationPair[] {
  const matrix = asRecord(detail?.rawMetrics?.['associations']);
  const seen = new Set<string>();
  const pairs: AssociationPair[] = [];
  for (const [left, row] of Object.entries(matrix)) {
    for (const [right, value] of Object.entries(asRecord(row))) {
      const score = num(value);
      if (left === right || score === null || Math.abs(score) < 0.1) {
        continue;
      }
      const key = [left, right].sort().join('\u0000');
      if (seen.has(key)) {
        continue;
      }
      seen.add(key);
      pairs.push({ left, right, score });
    }
  }
  return pairs.sort((a, b) => Math.abs(b.score) - Math.abs(a.score)).slice(0, limit);
}

export function typeMix(table: TableAnalysisView): { label: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const column of table.columns) {
    const label = typeLabel(column.dataType);
    counts.set(label, (counts.get(label) ?? 0) + 1);
  }
  return [...counts.entries()].map(([label, count]) => ({ label, count })).sort((a, b) => b.count - a.count);
}
