import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { RuleResults, VersionDiff } from '../../core/models';
import { WorkspaceStore } from '../../core/workspace.store';
import { EmptyState, ScoreRing, Skeleton } from '../../shared/components';
import { scoreTone } from '../../shared/format';
import { Icon } from '../../shared/icon';
import { buildReport, reportFileName, reportMarkdown } from './report';

/**
 * /datasets/:datasetId/versions/:versionId/report: the whole version on one printable page.
 * "Print" opens the browser dialog, whose "Save as PDF" makes the PDF; Markdown is a download.
 */
@Component({
  selector: 'app-report-page',
  imports: [RouterLink, Icon, ScoreRing, Skeleton, EmptyState],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './report.page.html',
  styleUrl: './report.page.css'
})
export class ReportPage {
  readonly store = inject(WorkspaceStore);
  private readonly api = inject(ApiService);
  private readonly document = inject(DOCUMENT);
  private readonly title = inject(Title);

  readonly datasetId = input.required<string>();
  readonly versionId = input.required<string>();

  private readonly rules = signal<RuleResults | null>(null);
  private readonly diff = signal<VersionDiff | null>(null);

  readonly report = computed(() => {
    const details = this.store.details();
    const dataset = this.store.selectedDataset();
    return details && dataset && details.version.id === Number(this.versionId())
      ? buildReport({ datasetName: dataset.name, details, rules: this.rules(), diff: this.diff() })
      : null;
  });
  readonly tone = computed(() => scoreTone(this.store.details()?.qualityScore));

  constructor() {
    effect(() => {
      const datasetId = Number(this.datasetId());
      const versionId = Number(this.versionId());
      untracked(() => {
        this.store.open(datasetId, versionId);
        this.rules.set(null);
        this.diff.set(null);
        this.api.ruleResults(datasetId, versionId).subscribe({ next: (rules) => this.rules.set(rules), error: () => undefined });
        this.api.diff(datasetId, versionId).subscribe({ next: (diff) => this.diff.set(diff), error: () => undefined });
      });
    });

    effect(() => {
      const report = this.report();
      this.title.setTitle(report ? report.title : 'Data quality report');
    });

    // The report is printed on white paper whatever theme is on screen.
    const root = this.document.documentElement;
    let previousTheme: string | null = null;
    const beforePrint = () => {
      previousTheme = root.getAttribute('data-theme');
      root.setAttribute('data-theme', 'light');
    };
    const afterPrint = () => {
      if (previousTheme === null) {
        root.removeAttribute('data-theme');
      } else {
        root.setAttribute('data-theme', previousTheme);
      }
    };
    const view = this.document.defaultView;
    view?.addEventListener('beforeprint', beforePrint);
    view?.addEventListener('afterprint', afterPrint);
    inject(DestroyRef).onDestroy(() => {
      view?.removeEventListener('beforeprint', beforePrint);
      view?.removeEventListener('afterprint', afterPrint);
    });
  }

  print(): void {
    this.document.defaultView?.print();
  }

  downloadMarkdown(): void {
    const report = this.report();
    if (!report) {
      return;
    }
    const blob = new Blob([reportMarkdown(report)], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = this.document.createElement('a');
    link.href = url;
    link.download = reportFileName(report);
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
