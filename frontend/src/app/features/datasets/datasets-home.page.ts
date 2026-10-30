import { ChangeDetectionStrategy, Component, effect, inject, input, untracked } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { LayoutService } from '../../core/layout.service';
import { WorkspaceStore } from '../../core/workspace.store';
import { EmptyState, Skeleton } from '../../shared/components';

/**
 * /datasets and /datasets/:datasetId: opens the most relevant version, or shows the
 * first-run empty state.
 */
@Component({
  selector: 'app-datasets-home',
  imports: [EmptyState, Skeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (store.datasetsError()) {
      <app-empty-state title="Could not load datasets" [message]="store.datasetsError() ?? ''" icon="!">
        <button type="button" class="btn btn--secondary" (click)="store.loadDatasets()">Try again</button>
      </app-empty-state>
    } @else if (store.datasetsLoaded() && store.datasets().length === 0) {
      <app-empty-state
        title="No datasets yet"
        message="Upload one or more CSV files to profile them, check their quality and map how the tables relate."
        icon="⬆"
      >
        <button type="button" class="btn btn--primary" (click)="layout.openUpload()">New analysis</button>
      </app-empty-state>
    } @else {
      <div class="card" aria-busy="true"><app-skeleton [count]="4" [height]="20" /></div>
    }
  `
})
export class DatasetsHomePage {
  readonly store = inject(WorkspaceStore);
  readonly layout = inject(LayoutService);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  readonly datasetId = input<string | undefined>(undefined);

  constructor() {
    inject(Title).setTitle('Data Quality');
    effect(() => {
      const datasets = this.store.sortedDatasets();
      if (!this.store.datasetsLoaded() || datasets.length === 0) {
        return;
      }
      const requested = Number(this.datasetId());
      const dataset = datasets.find((item) => item.id === requested) ?? datasets[0];
      untracked(() => this.openLatest(dataset.id, dataset.latestVersion?.id));
    });
  }

  private openLatest(datasetId: number, versionId: number | undefined): void {
    if (versionId) {
      this.router.navigate(['/datasets', datasetId, 'versions', versionId, 'overview'], { replaceUrl: true });
      return;
    }
    this.api.versions(datasetId).subscribe((versions) => {
      if (versions[0]) {
        this.router.navigate(['/datasets', datasetId, 'versions', versions[0].id, 'overview'], { replaceUrl: true });
      }
    });
  }
}
