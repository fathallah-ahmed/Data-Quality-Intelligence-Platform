import { ChangeDetectionStrategy, Component, effect, inject, input, untracked } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { WorkspaceStore } from '../../core/workspace.store';
import { Skeleton } from '../../shared/components';

/**
 * /datasets/:datasetId: opens the latest version of that dataset. A dataset that does not exist (any more)
 * sends the user back to the list of datasets.
 */
@Component({
  selector: 'app-datasets-home',
  imports: [Skeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="card" aria-busy="true"><app-skeleton [count]="4" [height]="22" /></div>`
})
export class DatasetsHomePage {
  readonly store = inject(WorkspaceStore);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  readonly datasetId = input<string | undefined>(undefined);

  constructor() {
    inject(Title).setTitle('Data Quality');
    effect(() => {
      const datasets = this.store.datasets();
      if (!this.store.datasetsLoaded()) {
        return;
      }
      const dataset = datasets.find((item) => item.id === Number(this.datasetId()));
      untracked(() => (dataset ? this.openLatest(dataset.id, dataset.latestVersion?.id) : this.router.navigate(['/datasets'], { replaceUrl: true })));
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
