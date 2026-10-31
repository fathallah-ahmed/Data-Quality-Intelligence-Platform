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

const TYPE_NAMES: Record<string, string> = {
  TYPE_NUM: 'numerical',
  TYPE_CAT: 'categorical',
  TYPE_TEXT: 'text',
  TYPE_BOOL: 'boolean',
  TYPE_DATE: 'date'
};

/**
 * Results stored by older pipeline versions embed a JSON type count in the text
 * (`Type mix: {"TYPE_NUM": 2}`). Rewrite it as words so no raw JSON reaches the screen.
 */
export function cleanLegacyText(text: string): string {
  return text.replace(/Type mix: (\{[^{}]*\})/g, (match, json: string) => {
    try {
      const counts = JSON.parse(json) as Record<string, number>;
      const parts = Object.entries(counts)
        .sort((a, b) => b[1] - a[1])
        .map(([type, count]) => `${count} ${TYPE_NAMES[type] ?? type.replace('TYPE_', '').toLowerCase()}`);
      return parts.length ? `Column types: ${parts.join(', ')}` : '';
    } catch {
      return match;
    }
  });
}

/** Text at a path; arrays and objects are turned into readable sentences, never JSON. */
export function insightText(source: unknown, ...path: string[]): string {
  const value = pathValue(source, ...path);
  if (typeof value === 'string') {
    return cleanLegacyText(value.trim());
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
    return cleanLegacyText(item.trim());
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
    const parts = [`${priority}${field}${formatInsightItem(main)}`];
    if (record['impact']) {
      parts.push(`Impact: ${formatInsightItem(record['impact'])}`);
    }
    if (record['effort']) {
      parts.push(`Effort: ${formatInsightItem(record['effort'])}`);
    }
    if (Array.isArray(record['steps']) && record['steps'].length > 0) {
      parts.push(`Steps: ${record['steps'].map((step) => formatInsightItem(step).replace(/\.+$/, '')).join(' → ')}`);
    }
    return parts.map((part) => part.replace(/\.+$/, '')).join('. ') + '.';
  }
  return Object.entries(record)
    .filter(([, value]) => value !== null && value !== undefined && value !== '')
    .map(([key, value]) => `${humanize(key)}: ${formatInsightItem(value)}`)
    .join(' · ');
}
