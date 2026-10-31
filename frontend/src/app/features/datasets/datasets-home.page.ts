import { ChangeDetectionStrategy, Component, effect, inject, input, untracked } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { LayoutService } from '../../core/layout.service';
import { WorkspaceStore } from '../../core/workspace.store';
import { EmptyState, Skeleton } from '../../shared/components';
import { Icon } from '../../shared/icon';

/**
 * /datasets and /datasets/:datasetId: opens the most relevant version, or shows the
 * first-run welcome screen.
 */
@Component({
  selector: 'app-datasets-home',
  imports: [EmptyState, Skeleton, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (store.datasetsError()) {
      <app-empty-state title="Could not load datasets" [message]="store.datasetsError() ?? ''" icon="alert">
        <button type="button" class="btn btn--secondary" (click)="store.loadDatasets()"><app-icon name="refresh" [size]="16" /> Try again</button>
      </app-empty-state>
    } @else if (store.datasetsLoaded() && store.datasets().length === 0) {
      <section class="welcome">
        <p class="eyebrow">Welcome</p>
        <h1>Know your data before you trust it.</h1>
        <p class="welcome__lead">
          Drop one or more CSV files. Every table is profiled, scored and compared with its previous version,
          the relationships between tables are mapped, and an AI reviewer explains what to fix.
        </p>
        <div class="welcome__actions">
          <button type="button" class="btn btn--primary" (click)="layout.openUpload()"><app-icon name="upload" [size]="18" /> Start a new analysis</button>
          <span class="muted">or press <span class="kbd">N</span></span>
        </div>
        <ol class="steps">
          <li>
            <span class="steps__icon"><app-icon name="upload" [size]="20" /></span>
            <strong>Upload</strong>
            <span>One CSV file per table. Re-upload later to create a new version.</span>
          </li>
          <li>
            <span class="steps__icon"><app-icon name="activity" [size]="20" /></span>
            <strong>Profile</strong>
            <span>Airflow measures completeness, duplicates, distributions and keys.</span>
          </li>
          <li>
            <span class="steps__icon"><app-icon name="sparkles" [size]="20" /></span>
            <strong>Understand</strong>
            <span>Scores, a data-model map and AI insights tell you what needs attention.</span>
          </li>
        </ol>
      </section>
    } @else {
      <div class="card" aria-busy="true"><app-skeleton [count]="4" [height]="22" /></div>
    }
  `,
  styles: `
    .welcome { max-width: 860px; margin: var(--space-6) auto; padding: var(--space-8) var(--space-6); border-radius: var(--radius-xl);
      background: radial-gradient(600px 260px at 15% 0%, var(--primary-soft), transparent 70%), var(--surface); border: 1px solid var(--border);
      box-shadow: var(--shadow-md); animation: rise 300ms var(--ease); }
    h1 { margin: var(--space-2) 0 var(--space-3); font-size: clamp(1.75rem, 4vw, 2.6rem); font-weight: 750; letter-spacing: -0.03em; }
    .welcome__lead { max-width: 620px; margin: 0; font-size: var(--text-lg); color: var(--text-muted); line-height: 1.6; }
    .welcome__actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-3); margin-top: var(--space-5); }
    .steps { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: var(--space-4); margin: var(--space-8) 0 0; padding: 0; list-style: none; }
    .steps li { display: grid; gap: var(--space-1); padding: var(--space-4); border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--surface-raised); }
    .steps li span:last-child { font-size: var(--text-sm); color: var(--text-muted); }
    .steps__icon { display: grid; place-items: center; width: 40px; height: 40px; margin-bottom: var(--space-2); border-radius: var(--radius-md); background: var(--primary-soft); color: var(--primary); }
    @media (max-width: 640px) { .welcome { margin: 0; padding: var(--space-6) var(--space-4); } }
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
