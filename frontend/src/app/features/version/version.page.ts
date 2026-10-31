import { ChangeDetectionStrategy, Component, DestroyRef, HostListener, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { Router, RouterLink } from '@angular/router';
import { ApiService, apiErrorMessage } from '../../core/api.service';
import { ToastService } from '../../core/toast.service';
import { WorkspaceStore } from '../../core/workspace.store';
import { ConfirmDialog, EmptyState, Skeleton, StatusBadge, TabItem, Tabs } from '../../shared/components';
import { formatDuration, isActiveStatus, sourceTypeLabel } from '../../shared/format';
import { Icon } from '../../shared/icon';
import { DQ_PIPES } from '../../shared/pipes';
import { ignoreShortcut } from '../../shared/keyboard';
import { CompareTab } from '../compare/compare-tab';
import { DataModelTab } from '../data-model/data-model-tab';
import { InsightsTab } from '../insights/insights-tab';
import { OverviewTab } from '../overview/overview-tab';
import { RulesTab } from '../rules/rules-tab';
import { TablesTab } from '../tables/tables-tab';

export const VERSION_TABS = ['overview', 'tables', 'model', 'insights', 'compare', 'rules'] as const;
export type VersionTab = (typeof VERSION_TABS)[number];

/** /datasets/:datasetId/versions/:versionId/:tab?table=name — the URL holds the whole selection. */
@Component({
  selector: 'app-version-page',
  imports: [RouterLink, Icon, Tabs, StatusBadge, EmptyState, Skeleton, ConfirmDialog, OverviewTab, TablesTab, DataModelTab, InsightsTab, CompareTab, RulesTab, ...DQ_PIPES],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './version.page.html',
  styleUrl: './version.page.css'
})
export class VersionPage {
  readonly store = inject(WorkspaceStore);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);
  private readonly title = inject(Title);

  // Route parameters (bound by withComponentInputBinding).
  readonly datasetId = input.required<string>();
  readonly versionId = input.required<string>();
  readonly tab = input<string>('overview');
  readonly table = input<string | undefined>(undefined);
  /** ?against=<versionId>: the older version the Compare tab uses. */
  readonly against = input<string | undefined>(undefined);

  readonly now = signal(Date.now());
  readonly retrying = signal(false);
  readonly menuOpen = signal(false);
  readonly confirmDelete = signal(false);
  readonly deleting = signal(false);

  readonly activeTab = computed<VersionTab>(() => (VERSION_TABS as readonly string[]).includes(this.tab()) ? (this.tab() as VersionTab) : 'overview');
  readonly details = this.store.details;
  readonly datasetIdNumber = computed(() => Number(this.datasetId()));
  readonly againstId = computed(() => (this.against() ? Number(this.against()) : null));
  readonly dataset = this.store.selectedDataset;
  readonly status = computed(() => this.details()?.version.status ?? this.dataset()?.latestVersion?.status ?? null);
  readonly active = computed(() => isActiveStatus(this.status()));
  readonly sourceLabel = computed(() => sourceTypeLabel(this.dataset()?.sourceType, this.details()?.version.expectedTableCount ?? null));
  readonly duration = computed(() => {
    const version = this.details()?.version;
    if (!version) {
      return '';
    }
    const start = Date.parse(version.startedAt ?? version.uploadedAt);
    const end = version.finishedAt && !this.active() ? Date.parse(version.finishedAt) : this.now();
    return formatDuration(end - start);
  });
  readonly selectedTable = computed(() => {
    const name = this.store.selectedTableName();
    return this.details()?.tables.find((table) => table.tableName === name) ?? null;
  });
  readonly tabs = computed<TabItem[]>(() => {
    const base = ['/datasets', this.datasetId(), 'versions', this.versionId()];
    const tableCount = this.details()?.tables.filter((table) => table.changeStatus !== 'REMOVED').length ?? null;
    return [
      { id: 'overview', label: 'Overview', icon: 'overview', link: [...base, 'overview'], shortcut: '1' },
      { id: 'tables', label: 'Tables', icon: 'table', link: [...base, 'tables'], count: tableCount, shortcut: '2' },
      { id: 'model', label: 'Data model', icon: 'network', link: [...base, 'model'], shortcut: '3' },
      { id: 'insights', label: 'Insights', icon: 'lightbulb', link: [...base, 'insights'], shortcut: '4' },
      { id: 'compare', label: 'Compare', icon: 'swap', link: [...base, 'compare'], shortcut: '5' },
      { id: 'rules', label: 'Rules', icon: 'shield', link: [...base, 'rules'], shortcut: '6' }
    ];
  });

  constructor() {
    effect(() => {
      const datasetId = Number(this.datasetId());
      const versionId = Number(this.versionId());
      if (Number.isFinite(datasetId) && Number.isFinite(versionId)) {
        untracked(() => this.store.open(datasetId, versionId));
      }
    });

    // Keep the selected table in sync with ?table= (first table when absent).
    effect(() => {
      const details = this.details();
      const requested = this.table();
      if (!details) {
        return;
      }
      const names = details.tables.map((table) => table.tableName);
      const name = requested && names.includes(requested)
        ? requested
        : details.tables.find((table) => table.changeStatus !== 'REMOVED')?.tableName ?? names[0] ?? null;
      untracked(() => this.store.selectTable(name));
    });

    effect(() => {
      const name = this.dataset()?.name;
      this.title.setTitle(name ? `Data Quality – ${name}` : 'Data Quality');
    });

    // Tick every second while a run is active (elapsed time), every 30 s otherwise (relative times).
    const destroyRef = inject(DestroyRef);
    let timer: ReturnType<typeof setInterval> | undefined;
    effect(() => {
      const period = this.active() ? 1000 : 30000;
      clearInterval(timer);
      timer = setInterval(() => this.now.set(Date.now()), period);
    });
    destroyRef.onDestroy(() => clearInterval(timer));
  }

  readonly tableCount = computed(() => this.details()?.tables.filter((table) => table.changeStatus !== 'REMOVED').length ?? 0);

  /** Keys 1–4 switch tabs (ignored while typing or when a dialog is open). */
  @HostListener('document:keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    if (ignoreShortcut(event)) {
      return;
    }
    const tab = VERSION_TABS[Number(event.key) - 1];
    if (tab) {
      event.preventDefault();
      this.router.navigate(['/datasets', this.datasetId(), 'versions', this.versionId(), tab], { queryParamsHandling: 'preserve' });
    }
  }

  selectVersion(versionId: string): void {
    this.router.navigate(['/datasets', this.datasetId(), 'versions', versionId, this.activeTab()]);
  }

  setAgainst(versionId: number): void {
    this.router.navigate([], { queryParams: { against: versionId }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  selectTable(name: string): void {
    this.router.navigate([], { queryParams: { table: name }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  retry(): void {
    const details = this.details();
    if (!details || this.retrying()) {
      return;
    }
    this.retrying.set(true);
    this.api.rerun(Number(this.datasetId()), details.version.id).subscribe({
      next: (response) => {
        this.retrying.set(false);
        if (response.airflowTriggered) {
          this.toasts.success(`Version ${response.versionNumber} is running again.`);
        } else {
          this.toasts.error(`Airflow could not start the run: ${response.airflowMessage}`);
        }
        this.store.refresh();
      },
      error: (error) => {
        this.retrying.set(false);
        this.toasts.error(apiErrorMessage(error, 'Could not restart the analysis.'));
      }
    });
  }

  deleteDataset(): void {
    const dataset = this.dataset();
    if (!dataset) {
      return;
    }
    this.deleting.set(true);
    this.store.deleteDataset(dataset).subscribe({
      next: () => {
        this.deleting.set(false);
        this.confirmDelete.set(false);
        this.toasts.success(`Dataset “${dataset.name}” was deleted.`);
        this.router.navigate(['/datasets']);
      },
      error: (error) => {
        this.deleting.set(false);
        this.toasts.error(apiErrorMessage(error, 'Could not delete this dataset.'));
      }
    });
  }

  toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  askDelete(): void {
    this.menuOpen.set(false);
    this.confirmDelete.set(true);
  }
}
