import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, bufferTime, filter, tap } from 'rxjs';
import { ApiService, apiErrorMessage } from './api.service';
import { LiveEventsService } from './live-events.service';
import { DatasetSummary, LiveEvent, TableAnalysisView, VersionDetails, VersionSummary } from './models';
import { isActiveStatus } from '../shared/format';

interface Selection {
  datasetId: number | null;
  versionId: number | null;
}

/**
 * Shared state of the workspace: dataset list, the selected dataset/version/table and their
 * data. Silent refreshes (live events, polling) keep the current data on screen until the new
 * data arrives, so the page does not jump.
 */
@Injectable({ providedIn: 'root' })
export class WorkspaceStore {
  private readonly api = inject(ApiService);
  private readonly live = inject(LiveEventsService);
  private requestId = 0;

  readonly datasets = signal<DatasetSummary[]>([]);
  readonly datasetsLoaded = signal(false);
  readonly datasetsError = signal<string | null>(null);

  readonly selection = signal<Selection>({ datasetId: null, versionId: null });
  readonly versions = signal<VersionSummary[]>([]);
  readonly details = signal<VersionDetails | null>(null);
  readonly detailsLoading = signal(false);
  readonly detailsError = signal<string | null>(null);

  readonly selectedTableName = signal<string | null>(null);
  readonly tableDetail = signal<TableAnalysisView | null>(null);
  readonly tableDetailLoading = signal(false);

  readonly lastUpdated = signal<Date | null>(null);

  readonly selectedDataset = computed(() => {
    const id = this.selection().datasetId;
    return this.datasets().find((dataset) => dataset.id === id) ?? null;
  });

  /** Datasets, most recently updated first. */
  readonly sortedDatasets = computed(() =>
    [...this.datasets()].sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt))
  );

  constructor() {
    // Several events often arrive together (table ready + LLM ready): refresh once per burst.
    this.live.events
      .pipe(
        bufferTime(400),
        filter((events) => events.length > 0)
      )
      .subscribe((events) => this.onLiveEvents(events));
  }

  loadDatasets(): void {
    this.api.datasets().subscribe({
      next: (datasets) => {
        this.datasets.set(datasets);
        this.datasetsLoaded.set(true);
        this.datasetsError.set(null);
        this.lastUpdated.set(new Date());
      },
      error: (error) => {
        this.datasetsLoaded.set(true);
        this.datasetsError.set(apiErrorMessage(error, 'Could not load the datasets.'));
      }
    });
  }

  /** Show a dataset version (called when the route changes). */
  open(datasetId: number, versionId: number): void {
    const current = this.selection();
    if (current.datasetId === datasetId && current.versionId === versionId && this.details()) {
      return;
    }
    const datasetChanged = current.datasetId !== datasetId;
    this.selection.set({ datasetId, versionId });
    this.details.set(null);
    this.detailsError.set(null);
    this.tableDetail.set(null);
    if (datasetChanged) {
      this.versions.set([]);
    }
    this.loadVersions(datasetId);
    this.loadDetails(false);
  }

  /** Reload everything that is on screen without clearing it first. */
  refresh(): void {
    this.loadDatasets();
    const { datasetId } = this.selection();
    if (datasetId !== null) {
      this.loadVersions(datasetId);
      this.loadDetails(true);
    }
  }

  selectTable(tableName: string | null): void {
    if (this.selectedTableName() === tableName && this.tableDetail()?.tableName === tableName) {
      return;
    }
    this.selectedTableName.set(tableName);
    this.tableDetail.set(null);
    this.loadTableDetail(false);
  }

  deleteDataset(dataset: DatasetSummary): Observable<unknown> {
    return this.api.deleteDataset(dataset.id).pipe(
      tap(() => {
        this.datasets.update((datasets) => datasets.filter((item) => item.id !== dataset.id));
        if (this.selection().datasetId === dataset.id) {
          this.clearSelection();
        }
      })
    );
  }

  clearSelection(): void {
    this.requestId++;
    this.selection.set({ datasetId: null, versionId: null });
    this.versions.set([]);
    this.details.set(null);
    this.tableDetail.set(null);
    this.selectedTableName.set(null);
  }

  reset(): void {
    this.clearSelection();
    this.datasets.set([]);
    this.datasetsLoaded.set(false);
  }

  private loadVersions(datasetId: number): void {
    this.api.versions(datasetId).subscribe({
      next: (versions) => {
        if (this.selection().datasetId === datasetId) {
          this.versions.set(versions);
        }
      },
      error: () => undefined
    });
  }

  private loadDetails(silent: boolean): void {
    const { datasetId, versionId } = this.selection();
    if (datasetId === null || versionId === null) {
      return;
    }
    const request = ++this.requestId;
    if (!silent) {
      this.detailsLoading.set(true);
    }
    this.api.versionDetails(datasetId, versionId).subscribe({
      next: (details) => {
        if (request !== this.requestId) {
          return;
        }
        const previous = this.details();
        this.details.set(details);
        this.detailsLoading.set(false);
        this.detailsError.set(null);
        this.lastUpdated.set(new Date());
        if (this.tableChanged(previous, details)) {
          this.loadTableDetail(true);
        }
      },
      error: (error) => {
        if (request !== this.requestId) {
          return;
        }
        this.detailsLoading.set(false);
        if (!silent) {
          this.detailsError.set(apiErrorMessage(error, 'Could not load this version.'));
        }
      }
    });
  }

  /** Raw metrics are fetched only for the selected table, never for the whole version. */
  private loadTableDetail(silent: boolean): void {
    const { datasetId, versionId } = this.selection();
    const name = this.selectedTableName();
    const table = this.details()?.tables.find((item) => item.tableName === name);
    if (datasetId === null || versionId === null || !table || table.changeStatus === 'REMOVED') {
      this.tableDetail.set(null);
      return;
    }
    if (!silent) {
      this.tableDetailLoading.set(true);
    }
    this.api.tableDetails(datasetId, versionId, table.tableVersionId).subscribe({
      next: (detail) => {
        if (this.selectedTableName() === detail.tableName && this.selection().versionId === versionId) {
          this.tableDetail.set(detail);
        }
        this.tableDetailLoading.set(false);
      },
      error: () => this.tableDetailLoading.set(false)
    });
  }

  private tableChanged(previous: VersionDetails | null, next: VersionDetails): boolean {
    const name = this.selectedTableName();
    if (!name) {
      return false;
    }
    const before = previous?.tables.find((table) => table.tableName === name);
    const after = next.tables.find((table) => table.tableName === name);
    if (!after) {
      return false;
    }
    return !before
      || !this.tableDetail()
      || before.tableVersionId !== after.tableVersionId
      || before.llmStatus !== after.llmStatus
      || before.rowCount !== after.rowCount
      || before.qualityScore !== after.qualityScore;
  }

  private onLiveEvents(events: LiveEvent[]): void {
    const { datasetId, versionId } = this.selection();
    const onlyPolls = events.every((event) => event.type === 'POLL');
    if (onlyPolls) {
      // Polling fallback: refresh the list, and the open version only while it is running.
      this.loadDatasets();
      if (datasetId !== null && isActiveStatus(this.details()?.version.status)) {
        this.loadVersions(datasetId);
        this.loadDetails(true);
      }
      return;
    }
    this.loadDatasets();
    const concernsSelection = events.some((event) =>
      event.type === 'RECONNECTED' || event.datasetId === datasetId || event.versionId === versionId
    );
    if (datasetId !== null && concernsSelection) {
      this.loadVersions(datasetId);
      this.loadDetails(true);
    }
  }
}
