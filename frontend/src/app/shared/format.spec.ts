import {
  formatBytes,
  formatDuration,
  formatNumber,
  formatPercent,
  formatScore,
  formatValue,
  llmStatusInfo,
  relativeTime,
  scoreTone,
  sourceTypeLabel,
  statusLabel,
  statusTone,
  typeLabel
} from './format';
import { formatInsightItem, insightList, insightText } from './insight';

describe('format helpers', () => {
  it('formats percentages with one decimal', () => {
    expect(formatPercent(22.857)).toBe('22.9%');
    expect(formatPercent(0)).toBe('0.0%');
    expect(formatPercent(null)).toBe('–');
    expect(formatPercent(Number.NaN)).toBe('–');
  });

  it('adds thousands separators and trims decimals', () => {
    expect(formatNumber(1234567)).toBe('1,234,567');
    expect(formatNumber(3.14159)).toBe('3.14');
    expect(formatNumber(12345.678)).toBe('12,346');
    expect(formatNumber(undefined)).toBe('–');
  });

  it('formats raw values without leaking objects', () => {
    expect(formatValue(true)).toBe('Yes');
    expect(formatValue({ a: 1 })).toBe('–');
    expect(formatValue('')).toBe('–');
  });

  it('formats scores and picks a colour', () => {
    expect(formatScore(87.25)).toBe('87.3');
    expect(scoreTone(95)).toBe('success');
    expect(scoreTone(75)).toBe('warning');
    expect(scoreTone(40)).toBe('danger');
    expect(scoreTone(null)).toBe('neutral');
  });

  it('formats sizes and durations', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(50 * 1024 * 1024)).toBe('50 MB');
    expect(formatDuration(45_000)).toBe('45 s');
    expect(formatDuration(185_000)).toBe('3 min 05 s');
    expect(formatDuration(3_720_000)).toBe('1 h 02 min');
  });

  it('formats relative times', () => {
    const now = Date.parse('2026-10-30T12:00:00Z');
    expect(relativeTime('2026-10-30T11:59:50Z', now)).toBe('just now');
    expect(relativeTime('2026-10-30T11:55:00Z', now)).toBe('5 min ago');
    expect(relativeTime('2026-10-30T09:00:00Z', now)).toBe('3 h ago');
    expect(relativeTime('2026-10-29T12:00:00Z', now)).toBe('yesterday');
    expect(relativeTime('2026-10-27T12:00:00Z', now)).toBe('3 days ago');
    expect(relativeTime(null, now)).toBe('–');
  });

  it('turns status enums into human labels', () => {
    expect(statusLabel('PARTIAL')).toBe('Partial');
    expect(statusLabel('PENDING')).toBe('Queued');
    expect(statusLabel('SOMETHING_ELSE')).toBe('Something else');
    expect(statusTone('FAILED')).toBe('danger');
    expect(statusTone('RUNNING')).toBe('running');
    expect(sourceTypeLabel('CSV_COLLECTION', 3)).toBe('CSV · 3 tables');
    expect(sourceTypeLabel('CSV', 1)).toBe('CSV · 1 table');
    expect(typeLabel('int64')).toBe('Numerical');
    expect(typeLabel('object')).toBe('Categorical');
    expect(typeLabel('datetime64[ns]')).toBe('Date');
    expect(typeLabel('bool')).toBe('Boolean');
  });

  it('describes the LLM status', () => {
    expect(llmStatusInfo('ok')?.label).toBe('AI insights');
    expect(llmStatusInfo('unavailable')?.label).toBe('Deterministic only – Ollama unavailable');
    expect(llmStatusInfo('invalid')?.label).toBe('AI answer rejected');
    expect(llmStatusInfo(null)).toBeNull();
  });
});

describe('insight helpers', () => {
  const interpretation = {
    quality_assessment: { completeness: 'Good', issues: [{ field: 'email', issue: 'Missing values', impact: 'high' }] },
    steps: ['a', 'b']
  };

  it('reads nested text', () => {
    expect(insightText(interpretation, 'quality_assessment', 'completeness')).toBe('Good');
    expect(insightText(interpretation, 'missing', 'path')).toBe('');
  });

  it('formats issue objects as sentences, never JSON', () => {
    expect(insightList(interpretation, 'quality_assessment', 'issues')).toEqual(['email: Missing values. Impact: high.']);
    const fallback = formatInsightItem({ type_mix: { TYPE_NUM: 1 } });
    expect(fallback).not.toContain('{');
    expect(formatInsightItem({ priority: 1, action: 'Fix keys', steps: ['dedupe', 'validate'] }))
      .toBe('P1 · Fix keys. Steps: dedupe → validate.');
  });
});
