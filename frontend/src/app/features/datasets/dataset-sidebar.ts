import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LayoutService } from '../../core/layout.service';
import { DatasetSummary } from '../../core/models';
import { WorkspaceStore } from '../../core/workspace.store';
import { Skeleton, StatusBadge } from '../../shared/components';
import { sourceTypeLabel } from '../../shared/format';
import { DQ_PIPES } from '../../shared/pipes';

@Component({
  selector: 'app-dataset-sidebar',
  imports: [RouterLink, StatusBadge, Skeleton, ...DQ_PIPES],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dataset-sidebar.html',
  styleUrl: './dataset-sidebar.css'
})
export class DatasetSidebar {
  readonly store = inject(WorkspaceStore);
  readonly layout = inject(LayoutService);
  readonly now = input(Date.now());
  readonly query = signal('');
  readonly statusFilter = signal<'all' | 'active' | 'issues'>('all');

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

  isSelected(dataset: DatasetSummary): boolean {
    return this.store.selection().datasetId === dataset.id;
  }
}
