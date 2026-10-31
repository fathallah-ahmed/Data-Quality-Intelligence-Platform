/** Pure formatting helpers shared by pipes, components and tests. */

export type Tone = 'success' | 'warning' | 'danger' | 'info' | 'running' | 'neutral';

const EMPTY = '–';

function isNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

/** `22.9%` (one decimal by default). Values are already percentages (0–100). */
export function formatPercent(value: number | null | undefined, digits = 1): string {
  return isNumber(value) ? `${value.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits })}%` : EMPTY;
}

/** Thousands separators; up to two decimals for non-integers (`1,234,567`, `3.14`). */
export function formatNumber(value: number | null | undefined, maxDecimals = 2): string {
  if (!isNumber(value)) {
    return EMPTY;
  }
  const decimals = Number.isInteger(value) ? 0 : Math.abs(value) >= 1000 ? 0 : maxDecimals;
  return value.toLocaleString('en-US', { maximumFractionDigits: decimals });
}

/** Number-or-text cell values coming from raw JSON. */
export function formatValue(value: unknown): string {
  if (value === null || value === undefined || value === '') {
    return EMPTY;
  }
  if (typeof value === 'number') {
    return formatNumber(value);
  }
  if (typeof value === 'boolean') {
    return value ? 'Yes' : 'No';
  }
  if (typeof value === 'object') {
    return Array.isArray(value) ? value.map(formatValue).join(', ') : EMPTY;
  }
  return String(value);
}

export function formatScore(value: number | null | undefined): string {
  return isNumber(value) ? value.toFixed(1) : EMPTY;
}

/** Colour scale for quality scores (0–100). */
export function scoreTone(value: number | null | undefined): Tone {
  if (!isNumber(value)) {
    return 'neutral';
  }
  if (value >= 90) {
    return 'success';
  }
  if (value >= 70) {
    return 'warning';
  }
  return 'danger';
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ['KB', 'MB', 'GB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(value >= 10 ? 0 : 1)} ${units[unit]}`;
}

/** "just now", "5 min ago", "3 h ago", "2 days ago", then the date. */
export function relativeTime(value: string | Date | null | undefined, now: number = Date.now()): string {
  if (!value) {
    return EMPTY;
  }
  const time = typeof value === 'string' ? Date.parse(value) : value.getTime();
  if (Number.isNaN(time)) {
    return EMPTY;
  }
  const seconds = Math.round((now - time) / 1000);
  if (seconds < 45) {
    return 'just now';
  }
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) {
    return `${minutes} min ago`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return `${hours} h ago`;
  }
  const days = Math.round(hours / 24);
  if (days < 7) {
    return days === 1 ? 'yesterday' : `${days} days ago`;
  }
  return new Date(time).toLocaleDateString();
}

/** Local date and time, used as the tooltip of relative times. */
export function absoluteTime(value: string | Date | null | undefined): string {
  if (!value) {
    return '';
  }
  const date = typeof value === 'string' ? new Date(value) : value;
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString();
}

/** `45 s`, `3 min 05 s`, `1 h 02 min`. */
export function formatDuration(milliseconds: number | null | undefined): string {
  if (!isNumber(milliseconds) || milliseconds < 0) {
    return EMPTY;
  }
  const totalSeconds = Math.round(milliseconds / 1000);
  if (totalSeconds < 60) {
    return `${totalSeconds} s`;
  }
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) {
    return `${minutes} min ${String(seconds).padStart(2, '0')} s`;
  }
  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, '0')} min`;
}

const STATUS_LABELS: Record<string, string> = {
  PENDING: 'Queued',
  RUNNING: 'Running',
  COMPLETED: 'Completed',
  PARTIAL: 'Partial',
  FAILED: 'Failed',
  NEW: 'New',
  MODIFIED: 'Modified',
  UNCHANGED: 'Unchanged',
  REMOVED: 'Removed',
  UNKNOWN: 'Unknown'
};

const STATUS_TONES: Record<string, Tone> = {
  PENDING: 'info',
  RUNNING: 'running',
  COMPLETED: 'success',
  PARTIAL: 'warning',
  FAILED: 'danger',
  NEW: 'info',
  MODIFIED: 'warning',
  UNCHANGED: 'neutral',
  REMOVED: 'danger',
  UNKNOWN: 'neutral'
};

export function statusLabel(status: string | null | undefined): string {
  const key = (status ?? 'UNKNOWN').toUpperCase();
  return STATUS_LABELS[key] ?? humanize(key);
}

export function statusTone(status: string | null | undefined): Tone {
  return STATUS_TONES[(status ?? 'UNKNOWN').toUpperCase()] ?? 'neutral';
}

export function isActiveStatus(status: string | null | undefined): boolean {
  const normalized = (status ?? '').toUpperCase();
  return normalized === 'PENDING' || normalized === 'RUNNING';
}

const LLM_LABELS: Record<string, { label: string; tone: Tone; hint: string }> = {
  ok: { label: 'AI insights', tone: 'success', hint: 'The LLM answer passed validation against the measured metrics.' },
  unavailable: {
    label: 'Deterministic only – Ollama unavailable',
    tone: 'warning',
    hint: 'Ollama could not be reached, so the insights are computed from the metrics only.'
  },
  invalid: {
    label: 'AI answer rejected',
    tone: 'warning',
    hint: 'The LLM answered but contradicted the metrics; the insights are computed from the metrics only.'
  },
  disabled: { label: 'AI not used', tone: 'neutral', hint: 'The LLM was not called for this result (empty table or LLM disabled).' },
  pending: { label: 'AI pending', tone: 'running', hint: 'Metrics are ready; the LLM analysis is still running.' }
};

export function llmStatusInfo(status: string | null | undefined): { label: string; tone: Tone; hint: string } | null {
  return status ? LLM_LABELS[status.toLowerCase()] ?? null : null;
}

/** `CSV_COLLECTION` + 3 tables -> `CSV · 3 tables`. */
export function sourceTypeLabel(sourceType: string | null | undefined, tableCount?: number | null): string {
  const base = (sourceType ?? 'CSV').toUpperCase().startsWith('CSV') ? 'CSV' : humanize(sourceType ?? '');
  if (tableCount === null || tableCount === undefined) {
    return base;
  }
  return `${base} · ${tableCount} ${tableCount === 1 ? 'table' : 'tables'}`;
}

export function isNumericType(value: string | null | undefined): boolean {
  const lower = (value ?? '').toLowerCase();
  return ['int', 'float', 'double', 'decimal', 'number', 'numeric', 'type_num'].some((token) => lower.includes(token));
}

/** pandas / Sweetviz dtypes -> Numerical, Date, Boolean, Categorical. */
export function typeLabel(value: string | null | undefined): string {
  const lower = (value ?? '').toLowerCase();
  if (!lower) {
    return 'Unknown';
  }
  if (lower.includes('bool')) {
    return 'Boolean';
  }
  if (isNumericType(lower)) {
    return 'Numerical';
  }
  if (lower.includes('date') || lower.includes('time')) {
    return 'Date';
  }
  if (lower.includes('text')) {
    return 'Text';
  }
  if (lower.includes('object') || lower.includes('string') || lower.includes('cat') || lower.includes('str')) {
    return 'Categorical';
  }
  return value ?? 'Unknown';
}

/** snake_case / UPPER_CASE -> "Sentence case". */
export function humanize(key: string): string {
  const text = key.replace(/[_-]+/g, ' ').trim().toLowerCase();
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : '';
}

/** Letter grade for a quality score, used by the score hero and the table list. */
export function qualityGrade(value: number | null | undefined): { grade: string; label: string } {
  if (!isNumber(value)) {
    return { grade: '–', label: 'Not scored yet' };
  }
  if (value >= 90) {
    return { grade: 'A', label: 'Healthy' };
  }
  if (value >= 75) {
    return { grade: 'B', label: 'Good, minor gaps' };
  }
  if (value >= 60) {
    return { grade: 'C', label: 'Needs attention' };
  }
  return { grade: 'D', label: 'Poor quality' };
}

/** Icon for a column type label from {@link typeLabel}. */
export function typeIcon(label: string): 'hash' | 'calendar' | 'boolean' | 'text' {
  switch (label) {
    case 'Numerical':
      return 'hash';
    case 'Date':
      return 'calendar';
    case 'Boolean':
      return 'boolean';
    default:
      return 'text';
  }
}
