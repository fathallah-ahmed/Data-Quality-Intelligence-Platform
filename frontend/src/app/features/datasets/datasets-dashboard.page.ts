import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { LayoutService } from '../../core/layout.service';
import { DatasetSummary } from '../../core/models';
import { WorkspaceStore } from '../../core/workspace.store';
import { EmptyState, ScoreRing, Skeleton, StatusBadge } from '../../shared/components';
import { avatarHue, initialsOf, isActiveStatus, qualityGrade, scoreTone, sourceTypeLabel } from '../../shared/format';
import { Icon } from '../../shared/icon';
import { CountUp } from '../../shared/motion';
import { DQ_PIPES } from '../../shared/pipes';

export type StatusFilter = 'all' | 'active' | 'attention' | 'healthy';
export type SortKey = 'recent' | 'name' | 'score-low' | 'score-high';

/** Below this score a dataset deserves a look even when nothing failed. */
export const LOW_SCORE = 60;

export const SORTS: { id: SortKey; label: string }[] = [
  { id: 'recent', label: 'Recently updated' },
  { id: 'name', label: 'Name (A to Z)' },
  { id: 'score-low', label: 'Quality: lowest first' },
  { id: 'score-high', label: 'Quality: highest first' }
];

function status(dataset: DatasetSummary): string {
  return (dataset.latestVersion?.status ?? '').toUpperCase();
}

/** A dataset needs attention when its last run failed or was partial, a rule fails, or its score is low. */
export function needsAttention(dataset: DatasetSummary): boolean {
  const score = dataset.latestScore;
  return status(dataset) === 'FAILED' || status(dataset) === 'PARTIAL' || (dataset.failedRules ?? 0) > 0
    || (score !== null && score !== undefined && score < LOW_SCORE);
}

/** Healthy: finished, nothing failing, and a score that is good enough. */
export function isHealthy(dataset: DatasetSummary): boolean {
  return status(dataset) === 'COMPLETED' && !needsAttention(dataset) && dataset.latestScore !== null && dataset.latestScore !== undefined;
}

/** The datasets to show for a search text, a status filter and a sort order (the list is not modified). */
export function visibleDatasets(datasets: DatasetSummary[], query: string, filter: StatusFilter, sort: SortKey): DatasetSummary[] {
  const text = query.trim().toLowerCase();
  const kept = datasets.filter((dataset) => {
    const matchesFilter = filter === 'all'
      || (filter === 'active' && isActiveStatus(status(dataset)))
      || (filter === 'attention' && needsAttention(dataset))
      || (filter === 'healthy' && isHealthy(dataset));
    return matchesFilter && (!text || dataset.name.toLowerCase().includes(text));
  });
  const score = (dataset: DatasetSummary, missing: number) => dataset.latestScore ?? missing;
  switch (sort) {
    case 'name':
      return kept.sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
    case 'score-low':
      return kept.sort((a, b) => score(a, 101) - score(b, 101));
    case 'score-high':
      return kept.sort((a, b) => score(b, -1) - score(a, -1));
    default:
      return kept.sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt));
  }
}

/** The figures of the strip at the top of the page. */
export function workspaceStats(datasets: DatasetSummary[]): {
  datasets: number;
  tables: number;
  averageScore: number | null;
  attention: number;
  running: number;
} {
  const scored = datasets.filter((dataset) => dataset.latestScore !== null && dataset.latestScore !== undefined);
  return {
    datasets: datasets.length,
    tables: datasets.reduce((sum, dataset) => sum + (dataset.tableCount ?? 0), 0),
    averageScore: scored.length === 0 ? null : scored.reduce((sum, dataset) => sum + (dataset.latestScore ?? 0), 0) / scored.length,
    attention: datasets.filter(needsAttention).length,
    running: datasets.filter((dataset) => isActiveStatus(status(dataset))).length
  };
}

/** `/datasets`: every dataset at a glance, with what needs attention first in reach. */
@Component({
  selector: 'app-datasets-dashboard',
  imports: [RouterLink, EmptyState, ScoreRing, Skeleton, StatusBadge, Icon, CountUp, ...DQ_PIPES],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './datasets-dashboard.page.html',
  styleUrl: './datasets-dashboard.page.css'
})
export class DatasetsDashboardPage {
  readonly store = inject(WorkspaceStore);
  readonly layout = inject(LayoutService);

  readonly sorts = SORTS;
  readonly query = signal('');
  readonly filter = signal<StatusFilter>('all');
  readonly sort = signal<SortKey>('recent');
  readonly now = signal(Date.now());

  readonly stats = computed(() => workspaceStats(this.store.datasets()));
  readonly averageGrade = computed(() => qualityGrade(this.stats().averageScore));
  readonly shown = computed(() => visibleDatasets(this.store.datasets(), this.query(), this.filter(), this.sort()));
  readonly filtered = computed(() => this.query().trim() !== '' || this.filter() !== 'all');
  readonly filters = computed<{ id: StatusFilter; label: string; count: number }[]>(() => {
    const datasets = this.store.datasets();
    return [
      { id: 'all', label: 'All', count: datasets.length },
      { id: 'attention', label: 'Needs attention', count: datasets.filter(needsAttention).length },
      { id: 'healthy', label: 'Healthy', count: datasets.filter(isHealthy).length },
      { id: 'active', label: 'Running', count: datasets.filter((dataset) => isActiveStatus(status(dataset))).length }
    ];
  });

  constructor() {
    inject(Title).setTitle('Data Quality – Your datasets');
    const timer = setInterval(() => this.now.set(Date.now()), 30000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  link(dataset: DatasetSummary): unknown[] {
    return dataset.latestVersion
      ? ['/datasets', dataset.id, 'versions', dataset.latestVersion.id, 'overview']
      : ['/datasets', dataset.id];
  }

  source(dataset: DatasetSummary): string {
    return sourceTypeLabel(dataset.sourceType, dataset.tableCount ?? dataset.latestVersion?.expectedTableCount ?? null);
  }

  running(dataset: DatasetSummary): boolean {
    return isActiveStatus(status(dataset));
  }

  grade(dataset: DatasetSummary): string {
    return qualityGrade(dataset.latestScore).grade;
  }

  tone(dataset: DatasetSummary): string {
    return needsAttention(dataset) ? (dataset.latestScore !== null && dataset.latestScore !== undefined ? scoreTone(dataset.latestScore) : 'danger') : 'success';
  }

  initials(name: string): string {
    return initialsOf(name);
  }

  hue(name: string): number {
    return avatarHue(name);
  }

  clearFilters(): void {
    this.query.set('');
    this.filter.set('all');
  }
}
