import { humanize } from './format';

/** Helpers to read the loosely-typed LLM / pipeline JSON without leaking raw objects to the UI. */

export function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}

export function asRecordArray(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value)
    ? (value.filter((item) => item && typeof item === 'object' && !Array.isArray(item)) as Record<string, unknown>[])
    : [];
}

export function pathValue(source: unknown, ...path: string[]): unknown {
  let current: unknown = source;
  for (const key of path) {
    current = asRecord(current)[key];
  }
  return current;
}

/** Text at a path; arrays and objects are turned into readable sentences, never JSON. */
export function insightText(source: unknown, ...path: string[]): string {
  const value = pathValue(source, ...path);
  if (typeof value === 'string') {
    return value.trim();
  }
  if (Array.isArray(value)) {
    return value.map(formatInsightItem).filter(Boolean).join('\n');
  }
  if (value && typeof value === 'object') {
    return formatInsightItem(value);
  }
  return typeof value === 'number' ? String(value) : '';
}

export function insightList(source: unknown, ...path: string[]): string[] {
  const value = pathValue(source, ...path);
  if (Array.isArray(value)) {
    return value.map(formatInsightItem).filter(Boolean);
  }
  if (typeof value === 'string' && value.trim()) {
    return [value.trim()];
  }
  if (value && typeof value === 'object') {
    return [formatInsightItem(value)];
  }
  return [];
}

export function insightRecords(source: unknown, ...path: string[]): Record<string, unknown>[] {
  return asRecordArray(pathValue(source, ...path));
}

export function recordText(record: Record<string, unknown>, ...keys: string[]): string {
  for (const key of keys) {
    const value = record[key];
    if (value !== undefined && value !== null && value !== '') {
      return formatInsightItem(value);
    }
  }
  return '';
}

/** One readable line for an issue / recommendation / step object returned by the LLM. */
export function formatInsightItem(item: unknown): string {
  if (typeof item === 'string') {
    return item.trim();
  }
  if (typeof item === 'number' || typeof item === 'boolean') {
    return String(item);
  }
  if (Array.isArray(item)) {
    return item.map(formatInsightItem).filter(Boolean).join(', ');
  }
  if (!item || typeof item !== 'object') {
    return '';
  }
  const record = item as Record<string, unknown>;
  const main = record['issue'] ?? record['summary'] ?? record['recommendation'] ?? record['action']
    ?? record['finding'] ?? record['question'] ?? record['area'];
  if (main !== undefined && main !== null && main !== '') {
    const priority = record['priority'] ? `P${record['priority']} · ` : '';
    const field = record['field'] && record['field'] !== 'table' ? `${formatInsightItem(record['field'])}: ` : '';
    const parts = [`${priority}${field}${formatInsightItem(main)}`.replace(/\.$/, '')];
    if (record['impact']) {
      parts.push(`Impact: ${formatInsightItem(record['impact'])}`);
    }
    if (record['effort']) {
      parts.push(`Effort: ${formatInsightItem(record['effort'])}`);
    }
    if (Array.isArray(record['steps']) && record['steps'].length > 0) {
      parts.push(`Steps: ${record['steps'].map(formatInsightItem).join(' → ')}`);
    }
    return parts.join('. ') + '.';
  }
  return Object.entries(record)
    .filter(([, value]) => value !== null && value !== undefined && value !== '')
    .map(([key, value]) => `${humanize(key)}: ${formatInsightItem(value)}`)
    .join(' · ');
}
