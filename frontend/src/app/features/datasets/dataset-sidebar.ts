import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LayoutService } from '../../core/layout.service';
import { DatasetSummary } from '../../core/models';
import { WorkspaceStore } from '../../core/workspace.store';
import { Skeleton } from '../../shared/components';
import { sourceTypeLabel, statusLabel, statusTone } from '../../shared/format';
import { Icon } from '../../shared/icon';
import { DQ_PIPES } from '../../shared/pipes';

const AVATAR_HUES = [168, 200, 32, 262, 340, 96];

@Component({
  selector: 'app-dataset-sidebar',
  imports: [RouterLink, Skeleton, Icon, ...DQ_PIPES],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dataset-sidebar.html',
  styleUrl: './dataset-sidebar.css',
  host: { '[class.collapsed]': 'collapsed()' }
})
export class DatasetSidebar {
  readonly store = inject(WorkspaceStore);
  readonly layout = inject(LayoutService);
  readonly now = input(Date.now());
  readonly collapsed = input(false);
  readonly query = signal('');
  readonly statusFilter = signal<'all' | 'active' | 'issues'>('all');

  readonly counts = computed(() => {
    const datasets = this.store.datasets();
    const status = (dataset: DatasetSummary) => (dataset.latestVersion?.status ?? '').toUpperCase();
    return {
      all: datasets.length,
      active: datasets.filter((dataset) => ['PENDING', 'RUNNING'].includes(status(dataset))).length,
      issues: datasets.filter((dataset) => ['FAILED', 'PARTIAL'].includes(status(dataset))).length
    };
  });

  readonly filtered = computed(() => {
    const query = this.query().trim().toLowerCase();
    const filter = this.statusFilter();
    return this.store.sortedDatasets().filter((dataset) => {
      const status = (dataset.latestVersion?.status ?? '').toUpperCase();
      const matchesFilter = filter === 'all'
        || (filter === 'active' && (status === 'PENDING' || status === 'RUNNING'))
        || (filter === 'issues' && (status === 'FAILED' || status === 'PARTIAL'));
      return matchesFilter && (!query || dataset.name.toLowerCase().includes(query));
    });
  });

  link(dataset: DatasetSummary): unknown[] {
    return dataset.latestVersion
      ? ['/datasets', dataset.id, 'versions', dataset.latestVersion.id, 'overview']
      : ['/datasets', dataset.id];
  }

  source(dataset: DatasetSummary): string {
    return sourceTypeLabel(dataset.sourceType, dataset.latestVersion?.expectedTableCount ?? null);
  }

  tone(dataset: DatasetSummary): string {
    return statusTone(dataset.latestVersion?.status);
  }

  statusText(dataset: DatasetSummary): string {
    return statusLabel(dataset.latestVersion?.status ?? 'UNKNOWN');
  }

  initials(name: string): string {
    const words = name.replace(/[^a-zA-Z0-9]+/g, ' ').trim().split(' ').filter(Boolean);
    return ((words[0]?.[0] ?? '?') + (words[1]?.[0] ?? words[0]?.[1] ?? '')).toUpperCase();
  }

  hue(name: string): number {
    let hash = 0;
    for (const char of name) {
      hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
    }
    return AVATAR_HUES[hash % AVATAR_HUES.length];
  }

  isSelected(dataset: DatasetSummary): boolean {
    return this.store.selection().datasetId === dataset.id;
  }
}
