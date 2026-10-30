import { CommonModule, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, OnDestroy, OnInit, ViewEncapsulation, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { DatasetSidebar } from './components/dataset-sidebar/dataset-sidebar';
import { DataModelMapComponent } from './components/data-model-map/data-model-map';
import { LoginPanel } from './components/login-panel/login-panel';
import { TopBar } from './components/top-bar/top-bar';
import { UploadPanel } from './components/upload-panel/upload-panel';
import {
  AuthMe,
  ClassDiagramEntity,
  ClassDiagramRelation,
  ColumnMetricView,
  ContextScope,
  DataModelMap,
  DataModelNode,
  DatasetSummary,
  LiveEvent,
  TableAnalysisView,
  UploadResponse,
  VersionDetails,
  VersionSummary
} from './models/data-quality.models';

@Component({
  selector: 'app-root',
  imports: [CommonModule, DecimalPipe, FormsModule, TopBar, LoginPanel, UploadPanel, DatasetSidebar, DataModelMapComponent],
  templateUrl: './app.html',
  styleUrl: './app.css',
  encapsulation: ViewEncapsulation.None
})
export class App implements OnInit, OnDestroy {
  private readonly apiBase = 'http://localhost:8081/api';
  private readonly refreshMs = 5000;
  private refreshTimer?: ReturnType<typeof setInterval>;
  private sseAbortController?: AbortController;
  private sseReconnectTimer?: ReturnType<typeof setTimeout>;
  private liveRefreshTimer?: ReturnType<typeof setTimeout>;

  datasets = signal<DatasetSummary[]>([]);
  versions = signal<VersionSummary[]>([]);
  details = signal<VersionDetails | null>(null);
  selectedDataset = signal<DatasetSummary | null>(null);
  selectedVersionId = signal<number | null>(null);
  selectedTableName = signal<string | null>(null);
  datasetName = '';
  selectedFiles: File[] = [];
  contextScope: ContextScope = 'none';
  showUploadContext = false;
  globalDescription = '';
  globalQuestion = '';
  tableDescriptions: Record<string, string> = {};
  loginUsername = 'admin';
  loginPassword = '';
  loading = signal(false);
  uploading = signal(false);
  loggingIn = signal(false);
  deletingDatasetId = signal<number | null>(null);
  refreshing = signal(false);
  autoRefresh = signal(true);
  sseConnected = signal(false);
  liveTransport = signal<'sse' | 'polling' | 'connecting'>('polling');
  lastUpdated = signal<Date | null>(null);
  authHeader = signal<string | null>(localStorage.getItem('dq_auth_header'));
  currentUser = signal<AuthMe | null>(null);
  duplicatePrompt = signal<UploadResponse | null>(null);
  message = signal<string | null>(null);
  error = signal<string | null>(null);

  selectedTable = computed(() => {
    const details = this.details();
    const tableName = this.selectedTableName();
    return details?.tables.find((table) => table.tableName === tableName) ?? details?.tables[0] ?? null;
  });

  liveStatus = computed(() => this.details()?.version.status ?? this.selectedDataset()?.latestVersion?.status ?? 'IDLE');
  isAuthenticated = computed(() => !!this.authHeader() && !!this.currentUser());

  constructor(private readonly http: HttpClient) {}

  ngOnInit(): void {
    if (this.authHeader()) {
      this.verifySavedLogin();
    }
    this.startAutoRefresh();
  }

  ngOnDestroy(): void {
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer);
    }
    if (this.liveRefreshTimer) {
      clearTimeout(this.liveRefreshTimer);
    }
    this.disconnectEventStream();
  }

  loadDatasets(silent = false, preferredDatasetId?: number, preferredVersionId?: number): void {
    if (!this.authHeader()) {
      return;
    }
    this.ensureEventStream();
    if (silent) {
      this.refreshing.set(true);
    } else {
      this.loading.set(true);
    }
    this.error.set(null);
    this.http.get<DatasetSummary[]>(`${this.apiBase}/datasets`, this.authOptions())
      .pipe(finalize(() => silent ? this.refreshing.set(false) : this.loading.set(false)))
      .subscribe({
        next: (datasets) => {
          this.datasets.set(datasets);
          this.lastUpdated.set(new Date());
          const selected = this.selectedDataset();
          const selectedUpdate = selected ? datasets.find((dataset) => dataset.id === selected.id) : null;
          if (selectedUpdate) {
            this.selectedDataset.set(selectedUpdate);
          } else if (selected) {
            this.selectedDataset.set(null);
            this.selectedVersionId.set(null);
            this.selectedTableName.set(null);
            this.versions.set([]);
            this.details.set(null);
          }
          if (preferredDatasetId) {
            const preferred = datasets.find((dataset) => dataset.id === preferredDatasetId);
            if (preferred) {
              this.selectDataset(preferred, preferredVersionId);
            }
            return;
          }
          if (!this.selectedDataset() && datasets.length > 0) {
            this.selectDataset(this.preferredDataset(datasets));
          }
        },
        error: (error) => this.handleApiError(error, 'Backend is not reachable on http://localhost:8081')
      });
  }

  selectDataset(dataset: DatasetSummary, preferredVersionId?: number): void {
    this.selectedDataset.set(dataset);
    this.details.set(null);
    this.selectedTableName.set(null);
    this.http.get<VersionSummary[]>(`${this.apiBase}/datasets/${dataset.id}/versions`, this.authOptions()).subscribe({
      next: (versions) => {
        this.versions.set(versions);
        const preferred = preferredVersionId ?? versions[0]?.id ?? null;
        this.selectedVersionId.set(preferred);
        if (preferred) {
          this.loadVersion(preferred);
        }
      },
      error: (error) => this.handleApiError(error, 'Could not load dataset versions')
    });
  }

  loadVersion(versionId: number | string): void {
    this.loadVersionDetails(versionId);
  }

  private loadVersionDetails(versionId: number | string, silent = false): void {
    const dataset = this.selectedDataset();
    const id = Number(versionId);
    if (!dataset || Number.isNaN(id)) {
      return;
    }
    const previousTableName = this.selectedTableName();
    this.selectedVersionId.set(id);
    if (silent) {
      this.refreshing.set(true);
    } else {
      this.loading.set(true);
    }
    this.http.get<VersionDetails>(`${this.apiBase}/datasets/${dataset.id}/versions/${id}`, this.authOptions())
      .pipe(finalize(() => silent ? this.refreshing.set(false) : this.loading.set(false)))
      .subscribe({
        next: (details) => {
          this.details.set(details);
          const tableStillExists = details.tables.some((table) => table.tableName === previousTableName);
          this.selectedTableName.set(tableStillExists ? previousTableName : details.tables[0]?.tableName ?? null);
          this.lastUpdated.set(new Date());
        },
        error: (error) => this.handleApiError(error, 'Could not load version analysis')
      });
  }

  onFilesSelected(files: File[]): void {
    this.selectedFiles = files;
    const nextDescriptions: Record<string, string> = {};
    for (const file of this.selectedFiles) {
      const tableName = this.tableNameFromFile(file);
      nextDescriptions[tableName] = this.tableDescriptions[tableName] ?? '';
    }
    this.tableDescriptions = nextDescriptions;
  }

  upload(rerunExisting = false): void {
    if (this.selectedFiles.length === 0) {
      this.error.set('Choose one or more CSV table files first');
      return;
    }

    const formData = new FormData();
    for (const file of this.selectedFiles) {
      formData.append('files', file);
    }
    if (this.datasetName.trim()) {
      formData.append('name', this.datasetName.trim());
    }
    formData.append('contextScope', this.showUploadContext ? this.contextScope : 'none');
    if (this.showUploadContext && this.usesGlobalContext()) {
      formData.append('globalDescription', this.globalDescription.trim());
    }
    if (this.showUploadContext && this.globalQuestion.trim()) {
      formData.append('globalQuestion', this.globalQuestion.trim());
    }
    if (this.showUploadContext && this.usesTableContext()) {
      formData.append('tableDescriptionsJson', JSON.stringify(this.activeTableDescriptions()));
    }

    this.uploading.set(true);
    this.error.set(null);
    this.message.set(null);
    if (rerunExisting) {
      this.duplicatePrompt.set(null);
    }
    this.http.post<UploadResponse>(`${this.apiBase}/datasets/upload?rerunExisting=${rerunExisting}`, formData, this.authOptions())
      .pipe(finalize(() => this.uploading.set(false)))
      .subscribe({
        next: (response) => {
          if (response.duplicateVersion && !response.rerunStarted) {
            this.duplicatePrompt.set(response);
            this.message.set(response.duplicateMessage ?? `Version ${response.versionNumber} already exists for this dataset.`);
            this.loadDatasets(false, response.datasetId, response.versionId);
            return;
          }
          this.duplicatePrompt.set(null);
          this.message.set(response.airflowTriggered
            ? response.rerunStarted
              ? `Existing version v${response.versionNumber} is running again (${response.fileCount} table file(s)).`
              : `Upload saved (${response.fileCount} table file(s)). Airflow run ${response.pipelineRunId} started.`
            : `Upload saved, but Airflow trigger failed: ${response.airflowMessage}`);
          this.datasetName = '';
          this.selectedFiles = [];
          this.resetContextInputs();
          this.loadDatasets(false, response.datasetId, response.versionId);
        },
        error: (error) => this.handleApiError(error, 'Upload failed. Check backend logs and file type.')
      });
  }

  runDuplicateAgain(): void {
    this.upload(true);
  }

  openDuplicateVersion(): void {
    const duplicate = this.duplicatePrompt();
    if (!duplicate) {
      return;
    }
    this.loadDatasets(false, duplicate.datasetId, duplicate.versionId);
  }

  dismissDuplicatePrompt(): void {
    this.duplicatePrompt.set(null);
  }

  deleteDataset(dataset: DatasetSummary, event?: Event): void {
    event?.stopPropagation();
    const confirmed = window.confirm(`Delete "${dataset.name}" and all its versions, table analysis, LLM results, and stored files?`);
    if (!confirmed) {
      return;
    }

    this.deletingDatasetId.set(dataset.id);
    this.error.set(null);
    this.message.set(null);
    this.http.delete<{ status: string }>(`${this.apiBase}/datasets/${dataset.id}`, this.authOptions())
      .pipe(finalize(() => this.deletingDatasetId.set(null)))
      .subscribe({
        next: () => {
          if (this.selectedDataset()?.id === dataset.id) {
            this.selectedDataset.set(null);
            this.selectedVersionId.set(null);
            this.selectedTableName.set(null);
            this.versions.set([]);
            this.details.set(null);
          }
          this.datasets.update((datasets) => datasets.filter((item) => item.id !== dataset.id));
          this.message.set(`Dataset "${dataset.name}" was deleted.`);
          this.loadDatasets(true);
        },
        error: (error) => this.handleApiError(error, 'Could not delete this dataset. Check backend logs and try again.')
      });
  }

  login(): void {
    if (!this.loginUsername.trim() || !this.loginPassword) {
      this.error.set('Enter the admin username and password');
      return;
    }
    const encoded = btoa(`${this.loginUsername.trim()}:${this.loginPassword}`);
    const header = `Basic ${encoded}`;
    this.loggingIn.set(true);
    this.error.set(null);
    this.http.get<AuthMe>(`${this.apiBase}/auth/me`, { headers: { Authorization: header } })
      .pipe(finalize(() => this.loggingIn.set(false)))
      .subscribe({
        next: (user) => {
          if (!user.roles.includes('ADMIN')) {
            this.error.set('This account is not an admin');
            return;
          }
          this.authHeader.set(header);
          this.currentUser.set(user);
          localStorage.setItem('dq_auth_header', header);
          this.loginPassword = '';
          this.message.set(`Welcome ${user.username}`);
          this.connectEventStream();
          this.loadDatasets();
        },
        error: () => this.error.set('Login failed. Use the admin account.')
      });
  }

  logout(): void {
    this.disconnectEventStream();
    localStorage.removeItem('dq_auth_header');
    this.authHeader.set(null);
    this.currentUser.set(null);
    this.datasets.set([]);
    this.versions.set([]);
    this.details.set(null);
    this.selectedDataset.set(null);
    this.selectedVersionId.set(null);
    this.selectedTableName.set(null);
    this.message.set(null);
  }

  selectTable(table: TableAnalysisView): void {
    this.selectedTableName.set(table.tableName);
  }

  refreshCurrent(): void {
    this.ensureEventStream(true);
    const selectedVersionId = this.selectedVersionId();
    if (selectedVersionId) {
      this.loadVersionDetails(selectedVersionId);
    } else {
      this.loadDatasets();
    }
  }

  toggleAutoRefresh(): void {
    const next = !this.autoRefresh();
    this.autoRefresh.set(next);
    if (next && this.isAuthenticated()) {
      this.connectEventStream();
    } else {
      this.disconnectEventStream();
    }
  }

  statusClass(status?: string): string {
    return (status ?? 'UNKNOWN').toLowerCase();
  }

  score(value?: number): number {
    return Math.round((value ?? 0) * 100) / 100;
  }

  isActiveStatus(status?: string): boolean {
    const normalized = (status ?? '').toUpperCase();
    return normalized === 'PENDING' || normalized === 'RUNNING';
  }

  expectedTables(details: VersionDetails): number {
    return details.version.expectedTableCount
      ?? details.globalAnalysis?.totalTables
      ?? Math.max(details.tables.length, 0);
  }

  readyTables(details: VersionDetails): number {
    return details.tables.length;
  }

  pipelineProgressPercent(details: VersionDetails): number {
    const status = details.version.status.toUpperCase();
    if (status === 'COMPLETED') {
      return 100;
    }
    if (status === 'FAILED') {
      return 100;
    }
    const total = this.expectedTables(details);
    const ready = this.readyTables(details);
    if (total <= 0) {
      return status === 'PENDING' ? 8 : 18;
    }
    const tableProgress = Math.round((ready / total) * 82);
    return Math.max(status === 'PENDING' ? 8 : 18, Math.min(96, 14 + tableProgress));
  }

  pipelineProgressLabel(details: VersionDetails): string {
    const status = details.version.status.toUpperCase();
    const total = this.expectedTables(details);
    const ready = this.readyTables(details);
    if (status === 'COMPLETED') {
      return `Complete: ${ready}/${total || ready} table(s) ready`;
    }
    if (status === 'FAILED') {
      return `Failed after ${ready}/${total || ready} table(s)`;
    }
    if (ready > 0) {
      return `Running: ${ready}/${total || ready} table result(s) ready`;
    }
    return status === 'PENDING' ? 'Queued in Airflow' : 'Profiling and analyzing tables';
  }

  pipelineSteps(details: VersionDetails): { label: string; state: string }[] {
    const status = details.version.status.toUpperCase();
    const total = this.expectedTables(details);
    const ready = this.readyTables(details);
    const completed = status === 'COMPLETED';
    const failed = status === 'FAILED';
    return [
      { label: 'Upload', state: 'done' },
      { label: 'Airflow', state: status === 'PENDING' ? 'active' : 'done' },
      { label: 'Tables', state: completed || (total > 0 && ready >= total) ? 'done' : ready > 0 ? 'active' : failed ? 'failed' : 'pending' },
      { label: 'Global', state: completed ? 'done' : details.globalAnalysis ? 'active' : failed ? 'failed' : 'pending' },
      { label: 'Done', state: completed ? 'done' : failed ? 'failed' : 'pending' }
    ];
  }

  changeSummary(details: VersionDetails): { label: string; count: number; percent: number; className: string }[] {
    const counts = new Map<string, number>();
    for (const table of details.tables) {
      const status = table.changeStatus || 'UNKNOWN';
      counts.set(status, (counts.get(status) ?? 0) + 1);
    }
    const total = Math.max(1, details.tables.length);
    return ['NEW', 'MODIFIED', 'UNCHANGED', 'REMOVED', 'UNKNOWN']
      .filter((label) => counts.has(label))
      .map((label) => ({
        label,
        count: counts.get(label) ?? 0,
        percent: ((counts.get(label) ?? 0) / total) * 100,
        className: label.toLowerCase()
      }));
  }

  unchangedMessage(details: VersionDetails): string {
    const expected = this.expectedTables(details);
    const unchanged = details.tables.filter((table) => table.changeStatus === 'UNCHANGED').length;
    if (expected > 0 && unchanged === expected) {
      return 'This uploaded version matches an existing version: all detected tables are unchanged.';
    }
    if (unchanged > 0) {
      return `${unchanged} table(s) are unchanged compared with the previous version.`;
    }
    return 'No unchanged tables have been detected for this version yet.';
  }

  portfolioQualityScore(details: VersionDetails): number {
    const direct = details.globalAnalysis?.globalQualityScore;
    if (direct != null) {
      return this.score(direct);
    }
    const scores = details.tables
      .map((table) => table.qualityScore)
      .filter((value): value is number => value != null);
    if (scores.length === 0) {
      return 0;
    }
    return this.score(this.sum(scores) / scores.length);
  }

  qualityGaugeStyle(details: VersionDetails): string {
    const value = Math.max(0, Math.min(100, this.portfolioQualityScore(details)));
    return `conic-gradient(#2d6a60 0 ${value}%, #dfe8e4 ${value}% 100%)`;
  }

  toggleUploadContext(): void {
    this.showUploadContext = !this.showUploadContext;
  }

  usesGlobalContext(): boolean {
    return this.contextScope === 'global' || this.contextScope === 'both';
  }

  usesTableContext(): boolean {
    return this.contextScope === 'per_table' || this.contextScope === 'both';
  }

  tableNameFromFile(file: File): string {
    return file.name.replace(/\.[^/.]+$/, '');
  }

  tableDescriptionFor(file: File): string {
    return this.tableDescriptions[this.tableNameFromFile(file)] ?? '';
  }

  setTableDescription(file: File, value: string): void {
    this.tableDescriptions = {
      ...this.tableDescriptions,
      [this.tableNameFromFile(file)]: value
    };
  }

  removeSelectedFile(fileToRemove: File): void {
    this.selectedFiles = this.selectedFiles.filter((file) => file !== fileToRemove);
    const nextDescriptions: Record<string, string> = {};
    for (const file of this.selectedFiles) {
      const tableName = this.tableNameFromFile(file);
      nextDescriptions[tableName] = this.tableDescriptions[tableName] ?? '';
    }
    this.tableDescriptions = nextDescriptions;
  }

  private startAutoRefresh(): void {
    this.refreshTimer = setInterval(() => {
      if (!this.autoRefresh() || !this.isAuthenticated()) {
        return;
      }
      if (this.sseConnected()) {
        return;
      }
      this.loadDatasets(true);
      const selectedVersionId = this.selectedVersionId();
      if (selectedVersionId) {
        this.loadVersionDetails(selectedVersionId, true);
      }
    }, this.refreshMs);
  }

  private async connectEventStream(): Promise<void> {
    const authHeader = this.authHeader();
    if (!authHeader || !this.autoRefresh() || this.sseAbortController) {
      return;
    }

    const controller = new AbortController();
    this.sseAbortController = controller;
    this.liveTransport.set('connecting');
    let shouldReconnect = false;

    try {
      const response = await fetch(`${this.apiBase}/events/stream`, {
        headers: {
          Authorization: authHeader,
          Accept: 'text/event-stream'
        },
        signal: controller.signal
      });

      if (response.status === 401 || response.status === 403) {
        this.handleApiError({ status: response.status }, 'Live stream authentication failed');
        return;
      }
      if (!response.ok || !response.body) {
        throw new Error(`SSE stream failed with HTTP ${response.status}`);
      }

      this.sseConnected.set(true);
      this.liveTransport.set('sse');
      await this.consumeEventStream(response.body, controller.signal);
      shouldReconnect = !controller.signal.aborted;
    } catch {
      shouldReconnect = !controller.signal.aborted;
    } finally {
      if (this.sseAbortController === controller) {
        this.sseAbortController = undefined;
      }
      this.sseConnected.set(false);
      if (this.autoRefresh()) {
        this.liveTransport.set('polling');
      }
      if (shouldReconnect) {
        this.scheduleSseReconnect();
      }
    }
  }

  private ensureEventStream(force = false): void {
    if (!this.autoRefresh() || !this.isAuthenticated()) {
      return;
    }
    if (force) {
      this.disconnectEventStream();
    }
    if (!this.sseConnected() && !this.sseAbortController && !this.sseReconnectTimer) {
      this.connectEventStream();
    }
  }

  private disconnectEventStream(): void {
    if (this.sseReconnectTimer) {
      clearTimeout(this.sseReconnectTimer);
      this.sseReconnectTimer = undefined;
    }
    this.sseAbortController?.abort();
    this.sseAbortController = undefined;
    this.sseConnected.set(false);
    this.liveTransport.set('polling');
  }

  private scheduleSseReconnect(): void {
    if (!this.autoRefresh() || !this.isAuthenticated() || this.sseReconnectTimer) {
      return;
    }
    this.sseReconnectTimer = setTimeout(() => {
      this.sseReconnectTimer = undefined;
      this.connectEventStream();
    }, 4000);
  }

  private async consumeEventStream(body: ReadableStream<Uint8Array>, signal: AbortSignal): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    try {
      while (!signal.aborted) {
        const { value, done } = await reader.read();
        if (done) {
          break;
        }
        buffer += decoder.decode(value, { stream: true });
        const chunks = buffer.split(/\r?\n\r?\n/);
        buffer = chunks.pop() ?? '';
        for (const chunk of chunks) {
          this.handleSseChunk(chunk);
        }
      }
    } finally {
      reader.releaseLock();
    }
  }

  private handleSseChunk(chunk: string): void {
    const lines = chunk.split(/\r?\n/);
    const dataLines: string[] = [];
    let eventType = 'message';

    for (const line of lines) {
      if (line.startsWith('event:')) {
        eventType = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart());
      }
    }

    if (dataLines.length === 0) {
      return;
    }

    try {
      const parsed = JSON.parse(dataLines.join('\n')) as Partial<LiveEvent>;
      this.handleLiveEvent({ ...parsed, type: parsed.type ?? eventType });
    } catch {
      // Ignore malformed stream chunks and keep the live connection open.
    }
  }

  private handleLiveEvent(event: LiveEvent): void {
    if (event.type === 'CONNECTED') {
      return;
    }
    this.scheduleLiveRefresh();
  }

  private scheduleLiveRefresh(): void {
    if (this.liveRefreshTimer || !this.isAuthenticated()) {
      return;
    }
    this.liveRefreshTimer = setTimeout(() => {
      this.liveRefreshTimer = undefined;
      this.loadDatasets(true);
      const selectedVersionId = this.selectedVersionId();
      if (selectedVersionId) {
        this.loadVersionDetails(selectedVersionId, true);
      }
    }, 250);
  }

  private verifySavedLogin(): void {
    this.http.get<AuthMe>(`${this.apiBase}/auth/me`, this.authOptions()).subscribe({
      next: (user) => {
        this.currentUser.set(user);
        this.connectEventStream();
        this.loadDatasets();
      },
      error: () => this.logout()
    });
  }

  private authOptions(): { headers: Record<string, string> } {
    const header = this.authHeader();
    return header ? { headers: { Authorization: header } } : { headers: {} };
  }

  private handleApiError(error: { status?: number }, fallback: string): void {
    if (error.status === 401 || error.status === 403) {
      this.error.set('Your admin session is not valid. Please log in again.');
      this.logout();
      return;
    }
    this.error.set(fallback);
  }

  private preferredDataset(datasets: DatasetSummary[]): DatasetSummary {
    return [...datasets]
      .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
      .find((dataset) => dataset.latestVersion?.status === 'COMPLETED') ?? datasets[datasets.length - 1];
  }

  private activeTableDescriptions(): Record<string, string> {
    return Object.entries(this.tableDescriptions)
      .filter(([, value]) => value.trim())
      .reduce<Record<string, string>>((result, [tableName, value]) => {
        result[tableName] = value.trim();
        return result;
      }, {});
  }

  private resetContextInputs(): void {
    this.showUploadContext = false;
    this.contextScope = 'none';
    this.globalDescription = '';
    this.globalQuestion = '';
    this.tableDescriptions = {};
  }

  tableFeatureRows(table: TableAnalysisView): Record<string, unknown>[] {
    const rawRows = this.asRecordArray(table.rawAnalysis?.['column_metrics']);
    if (rawRows.length > 0) {
      return rawRows;
    }
    return table.columns.map((column) => ({
      name: column.columnName,
      data_type: column.dataType,
      missing_count: column.missingCount,
      missing_percent: column.missingPercentage,
      distinct_count: column.uniqueCount,
      min_value: column.minValue,
      max_value: column.maxValue
    }));
  }

  sortedMissingColumns(table: TableAnalysisView): ColumnMetricView[] {
    return [...table.columns].sort((a, b) => (b.missingPercentage ?? 0) - (a.missingPercentage ?? 0));
  }

  numericColumns(table: TableAnalysisView): Record<string, unknown>[] {
    return this.tableFeatureRows(table).filter((feature) => this.isNumericType(String(feature['data_type'] ?? feature['type'] ?? '')));
  }

  typeBreakdown(table: TableAnalysisView): { label: string; count: number; percent: number }[] {
    const counts = new Map<string, number>();
    for (const column of table.columns) {
      const label = this.normalizeType(column.dataType);
      counts.set(label, (counts.get(label) ?? 0) + 1);
    }
    const total = Math.max(1, table.columns.length);
    return [...counts.entries()].map(([label, count]) => ({ label, count, percent: (count / total) * 100 }));
  }

  allFeatureRows(details: VersionDetails): { table: string; feature: Record<string, unknown> }[] {
    return details.tables.flatMap((table) => this.tableFeatureRows(table).map((feature) => ({ table: table.tableName, feature })));
  }

  maxTableRows(details: VersionDetails): number {
    return Math.max(1, ...details.tables.map((table) => table.rowCount ?? 0));
  }

  maxTableColumns(details: VersionDetails): number {
    return Math.max(1, ...details.tables.map((table) => table.columnCount ?? 0));
  }

  percent(value?: number, max = 100): number {
    if (!value || max <= 0) {
      return 0;
    }
    return Math.max(0, Math.min(100, (value / max) * 100));
  }

  featureValue(feature: Record<string, unknown>, ...keys: string[]): unknown {
    for (const key of keys) {
      const value = feature[key];
      if (value !== undefined && value !== null && value !== '') {
        return value;
      }
    }
    return '-';
  }

  topValues(feature: Record<string, unknown>): { label: string; value: unknown }[] {
    const values = feature['top_values'] ?? feature['value_counts'];
    if (Array.isArray(values)) {
      return values.slice(0, 8).map((item) => {
        if (item && typeof item === 'object') {
          const record = item as Record<string, unknown>;
          return {
            label: String(record['value'] ?? record['name'] ?? record['label'] ?? '-'),
            value: record['count'] ?? record['frequency'] ?? record['value_count'] ?? '-'
          };
        }
        return { label: String(item), value: '' };
      });
    }
    if (values && typeof values === 'object') {
      return Object.entries(values as Record<string, unknown>).slice(0, 8).map(([label, value]) => ({ label, value }));
    }
    return [];
  }

  featuresWithTopValues(table: TableAnalysisView): { name: string; values: { label: string; value: unknown }[] }[] {
    return this.tableFeatureRows(table)
      .map((feature) => ({
        name: String(this.featureValue(feature, 'name', 'column_name')),
        values: this.topValues(feature)
      }))
      .filter((item) => item.values.length > 0)
      .slice(0, 8);
  }

  classDiagramEntities(details: VersionDetails): ClassDiagramEntity[] {
    const relations = this.classDiagramRelations(details);
    const foreignKeys = new Set(relations.map((relation) => `${relation.sourceTable}.${relation.sourceColumn}`));
    return details.tables.map((table) => ({
      tableName: table.tableName,
      rowCount: table.rowCount ?? 0,
      columns: table.columns.map((column) => ({
        name: column.columnName,
        type: this.normalizeType(column.dataType),
        role: this.classDiagramColumnRole(table, column, foreignKeys)
      }))
    }));
  }

  classDiagramRelations(details: VersionDetails): ClassDiagramRelation[] {
    return this.mergeClassDiagramRelations(
      this.deterministicClassDiagramRelations(details),
      this.inferClassDiagramRelations(details)
    );
  }

  dataModelScopeLabel(details: VersionDetails): string {
    const sourceType = (this.selectedDataset()?.sourceType ?? '').toLowerCase();
    if (sourceType.includes('graph') || sourceType.includes('neo4j')) {
      return 'Graph database model';
    }
    if (sourceType.includes('mongo') || sourceType.includes('json') || sourceType.includes('nosql') || sourceType.includes('document')) {
      return 'NoSQL / document model';
    }
    if (sourceType.includes('sql') || sourceType.includes('mysql') || sourceType.includes('postgres') || this.classDiagramRelations(details).length > 0) {
      return 'Relational data model';
    }
    return 'Dataset structure model';
  }

  dataModelMap(details: VersionDetails): DataModelMap {
    const entities = this.classDiagramEntities(details);
    const relations = this.classDiagramRelations(details);
    const nodeWidth = 310;
    const headerHeight = 46;
    const fieldGap = 17;
    const nodePaddingBottom = 18;
    const minNodeHeight = 126;
    const gapX = 96;
    const margin = 88;
    const orderedEntities = this.dataModelGridEntities(entities, relations);
    const columnCount = Math.max(1, Math.min(orderedEntities.length || 1, Math.ceil(Math.sqrt(Math.max(1, orderedEntities.length)))));
    const rowEntities = orderedEntities.reduce<ClassDiagramEntity[][]>((rows, entity, index) => {
      const row = Math.floor(index / columnCount);
      rows[row] = [...(rows[row] ?? []), entity];
      return rows;
    }, []);
    const entityGridPosition = new Map<string, { row: number; column: number }>();
    rowEntities.forEach((row, rowIndex) => {
      row.forEach((entity, columnIndex) => {
        entityGridPosition.set(entity.tableName, { row: rowIndex, column: columnIndex });
      });
    });
    const laneCounts = this.dataModelLaneCounts(relations, entityGridPosition);
    const maxLaneCount = Math.max(1, ...laneCounts.values());
    const gapY = Math.max(130, 46 + maxLaneCount * 28);
    const rowHeights = rowEntities.map((row) =>
      Math.max(...row.map((entity) => Math.max(minNodeHeight, headerHeight + 25 + entity.columns.length * fieldGap + nodePaddingBottom)))
    );
    const rowTops = rowHeights.reduce<number[]>((tops, height, index) => {
      const previousTop = index === 0 ? margin : tops[index - 1] + rowHeights[index - 1] + gapY;
      tops.push(previousTop);
      return tops;
    }, []);
    const canvasWidth = margin * 2 + columnCount * nodeWidth + Math.max(0, columnCount - 1) * gapX;
    const nodes = rowEntities.flatMap((row, rowIndex) => row.map((entity, columnIndex) => {
      const fields = entity.columns.map((field, fieldIndex) => ({
        name: this.compactText(field.name, 27),
        type: this.compactText(field.type, 14),
        role: field.role,
        y: headerHeight + 25 + fieldIndex * fieldGap
      }));
      return {
        id: entity.tableName,
        label: this.compactText(entity.tableName, 24),
        subtitle: `${entity.rowCount.toLocaleString()} rows / ${entity.columns.length} fields`,
        x: margin + columnIndex * (nodeWidth + gapX),
        y: rowTops[rowIndex],
        width: nodeWidth,
        height: rowHeights[rowIndex],
        row: rowIndex,
        column: columnIndex,
        fields,
        moreCount: 0
      };
    }));
    const nodeById = new Map(nodes.map((node) => [node.id, node]));
    const relationsBySource = this.groupRelationsBy(relations, (relation) => relation.sourceTable);
    const relationsByTarget = this.groupRelationsBy(relations, (relation) => relation.targetTable);
    const laneIndexes = this.dataModelLaneIndexes(relations, entityGridPosition);
    const edges = relations.flatMap((relation, index) => {
      const source = nodeById.get(relation.sourceTable);
      const target = nodeById.get(relation.targetTable);
      if (!source || !target) {
        return [];
      }
      const sourceSiblings = relationsBySource.get(relation.sourceTable) ?? [];
      const targetSiblings = relationsByTarget.get(relation.targetTable) ?? [];
      const points = this.dataModelEdgePoints(
        source,
        target,
        Math.max(0, sourceSiblings.indexOf(relation)),
        Math.max(1, sourceSiblings.length),
        Math.max(0, targetSiblings.indexOf(relation)),
        Math.max(1, targetSiblings.length),
        rowTops,
        rowHeights,
        gapY,
        laneIndexes.get(index) ?? 0,
        laneCounts.get(this.dataModelLaneKey(source.row, target.row)) ?? 1
      );
      const cardinality = this.relationCardinality(details, relation);
      return [{
        id: `${relation.sourceTable}.${relation.sourceColumn}->${relation.targetTable}.${relation.targetColumn}-${index}`,
        sourceTable: relation.sourceTable,
        targetTable: relation.targetTable,
        label: this.compactText(relation.sourceColumn, 18),
        labelWidth: this.edgeLabelWidth(relation.sourceColumn),
        confidence: relation.confidence,
        reason: relation.reason,
        path: points.path,
        labelX: points.labelX,
        labelY: points.labelY,
        sourceCardinality: cardinality.source,
        targetCardinality: cardinality.target,
        sourceCardinalityX: points.sourceCardinalityX,
        sourceCardinalityY: points.sourceCardinalityY,
        targetCardinalityX: points.targetCardinalityX,
        targetCardinalityY: points.targetCardinalityY
      }];
    });
    const lastRowIndex = Math.max(0, rowHeights.length - 1);
    return {
      width: canvasWidth,
      height: rowTops[lastRowIndex] + (rowHeights[lastRowIndex] ?? minNodeHeight) + margin + gapY,
      nodes,
      edges
    };
  }

  downloadDataModelImage(details: VersionDetails): void {
    const datasetName = this.selectedDataset()?.name ?? 'dataset';
    const fileName = `${this.safeFileName(datasetName)}-v${details.version.versionNumber}-data-model.svg`;
    const blob = new Blob([this.dataModelSvg(details)], { type: 'image/svg+xml;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
    link.click();
    URL.revokeObjectURL(url);
  }

  topAssociationPairs(table: TableAnalysisView): { left: string; right: string; score: number }[] {
    const rawPairs = this.asRecordArray(table.rawMetrics?.['assoc_pairs'] ?? table.rawAnalysis?.['assoc_pairs']);
    if (rawPairs.length > 0) {
      return rawPairs.map((pair) => ({
        left: String(pair['left'] ?? pair['source'] ?? pair['feature_1'] ?? pair['0'] ?? ''),
        right: String(pair['right'] ?? pair['target'] ?? pair['feature_2'] ?? pair['1'] ?? ''),
        score: Number(pair['score'] ?? pair['strength'] ?? pair['correlation'] ?? 0)
      })).filter((pair) => pair.left || pair.right);
    }
    const associations = this.asRecord(table.rawMetrics?.['associations']);
    return Object.entries(associations)
      .filter(([, value]) => typeof value === 'number')
      .slice(0, 20)
      .map(([key, value]) => {
        const parts = key.split(/[|:]/);
        return { left: parts[0] ?? key, right: parts[1] ?? '', score: Number(value) };
      });
  }

  insightText(source: Record<string, unknown> | undefined, ...path: string[]): string {
    let current: unknown = source;
    for (const key of path) {
      current = this.asRecord(current)?.[key];
    }
    if (typeof current === 'string') {
      return current;
    }
    if (Array.isArray(current)) {
      return current.map((item) => this.formatInsightItem(item)).join('\n');
    }
    if (current && typeof current === 'object') {
      return this.formatInsightItem(current);
    }
    return '';
  }

  insightList(source: Record<string, unknown> | undefined, ...path: string[]): string[] {
    let current: unknown = source;
    for (const key of path) {
      current = this.asRecord(current)?.[key];
    }
    if (Array.isArray(current)) {
      return current.map((item) => this.formatInsightItem(item));
    }
    if (typeof current === 'string' && current.trim()) {
      return [current];
    }
    if (current && typeof current === 'object') {
      return [this.formatInsightItem(current)];
    }
    return [];
  }

  insightRecords(source: Record<string, unknown> | undefined, ...path: string[]): Record<string, unknown>[] {
    let current: unknown = source;
    for (const key of path) {
      current = this.asRecord(current)?.[key];
    }
    return this.asRecordArray(current);
  }

  recordText(record: Record<string, unknown>, ...keys: string[]): string {
    for (const key of keys) {
      const value = record[key];
      if (value !== undefined && value !== null && value !== '') {
        return this.formatInsightItem(value);
      }
    }
    return '';
  }

  recordList(record: Record<string, unknown>, key: string): string[] {
    const value = record[key];
    if (Array.isArray(value)) {
      return value.map((item) => this.formatInsightItem(item)).filter(Boolean);
    }
    const text = this.recordText(record, key);
    return text ? [text] : [];
  }

  globalInsight(details: VersionDetails, ...path: string[]): string {
    return this.insightText(details.globalAnalysis?.llmInterpretation, ...path)
      || this.insightText(details.globalAnalysis?.rawGlobalAnalysis, ...path)
      || '';
  }

  globalBusinessView(details: VersionDetails): string {
    const direct = this.globalInsight(details, 'portfolio_summary', 'business_view');
    if (this.isMeaningfulGlobalText(direct)) {
      return direct;
    }
    const tableNames = details.tables.map((table) => table.tableName).join(', ') || 'no tables yet';
    const rows = details.globalAnalysis?.totalRows ?? this.sum(details.tables.map((table) => table.rowCount ?? 0));
    const columns = details.globalAnalysis?.totalColumns ?? this.sum(details.tables.map((table) => table.columnCount ?? 0));
    return `This dataset version contains ${details.tables.length} table(s): ${tableNames}. Together they provide ${rows.toLocaleString()} rows and ${columns.toLocaleString()} columns for analysis.`;
  }

  globalQualityPosture(details: VersionDetails): string {
    const direct = this.globalInsight(details, 'portfolio_summary', 'data_quality_posture');
    if (this.isMeaningfulGlobalText(direct)) {
      return direct;
    }
    const totalMissing = this.sum(details.tables.map((table) => table.missingCount ?? 0));
    const totalDuplicates = this.sum(details.tables.map((table) => table.duplicateCount ?? 0));
    const highMissing = details.tables.flatMap((table) =>
      table.columns
        .filter((column) => (column.missingPercentage ?? 0) >= 50)
        .map((column) => `${table.tableName}.${column.columnName} (${this.score(column.missingPercentage)}%)`)
    );
    const duplicateText = totalDuplicates > 0 ? `${totalDuplicates.toLocaleString()} duplicate row(s)` : 'no duplicate rows reported';
    const missingText = totalMissing > 0 ? `${totalMissing.toLocaleString()} missing cell(s)` : 'no missing cells reported';
    const riskText = highMissing.length > 0 ? ` High-missing columns to review: ${highMissing.slice(0, 5).join(', ')}.` : ' No high-missing columns were detected.';
    return `Portfolio quality check found ${missingText} and ${duplicateText}.${riskText}`;
  }

  globalFinalAssessment(details: VersionDetails): string {
    const direct = this.globalInsight(details, 'final_assessment');
    if (this.isMeaningfulGlobalText(direct)) {
      return direct;
    }
    const recommendations = details.globalAnalysis?.recommendations;
    if (this.isMeaningfulGlobalText(recommendations)) {
      return recommendations ?? '';
    }
    const totalMissing = this.sum(details.tables.map((table) => table.missingCount ?? 0));
    const highMissingCount = details.tables.reduce(
      (count, table) => count + table.columns.filter((column) => (column.missingPercentage ?? 0) >= 50).length,
      0
    );
    if (highMissingCount > 0 || totalMissing > 0) {
      return `The dataset is usable for exploration, but ${highMissingCount} high-missing column(s) and ${totalMissing.toLocaleString()} missing cell(s) should be reviewed before business reporting or model training.`;
    }
    return 'The analyzed tables look structurally consistent from the available metrics. Continue with business validation and confirm key columns with stakeholders.';
  }

  formatInsightItem(item: unknown): string {
    if (typeof item === 'string') {
      return item;
    }
    if (!item || typeof item !== 'object') {
      return String(item ?? '');
    }

    const record = item as Record<string, unknown>;
    const priority = record['priority'] ? `P${record['priority']}: ` : '';
    const field = record['field'] ? `${record['field']}: ` : '';
    const issue = record['issue'] ?? record['summary'] ?? record['recommendation'] ?? record['question'];
    const impact = record['impact'] ? ` Impact: ${record['impact']}.` : '';
    const effort = record['effort'] ? ` Effort: ${record['effort']}.` : '';
    const steps = Array.isArray(record['steps']) ? ` Steps: ${record['steps'].map((step) => this.formatInsightItem(step)).join(' -> ')}.` : '';

    if (issue) {
      return `${priority}${field}${this.formatInsightItem(issue)}.${impact}${effort}${steps}`.replace(/\.\./g, '.').trim();
    }

    return Object.entries(record)
      .map(([key, value]) => `${this.humanizeKey(key)}: ${Array.isArray(value) ? value.map((entry) => this.formatInsightItem(entry)).join(', ') : this.formatInsightItem(value)}`)
      .join(' | ');
  }

  private inferClassDiagramRelations(details: VersionDetails): ClassDiagramRelation[] {
    const relations: ClassDiagramRelation[] = [];
    const seen = new Set<string>();

    const addRelation = (
      sourceTable: TableAnalysisView,
      sourceColumn: ColumnMetricView,
      targetTable: TableAnalysisView,
      targetColumn: ColumnMetricView,
      confidence: 'high' | 'medium',
      reason: string
    ) => {
      const key = `${sourceTable.tableName}.${sourceColumn.columnName}->${targetTable.tableName}.${targetColumn.columnName}`;
      if (seen.has(key)) {
        return;
      }
      seen.add(key);
      relations.push({
        sourceTable: sourceTable.tableName,
        sourceColumn: sourceColumn.columnName,
        targetTable: targetTable.tableName,
        targetColumn: targetColumn.columnName,
        confidence,
        reason
      });
    };

    for (const sourceTable of details.tables) {
      for (const sourceColumn of sourceTable.columns) {
        if (!this.isIdentifierColumn(sourceColumn.columnName)) {
          continue;
        }
        if (this.isOwnIdentifier(sourceTable, sourceColumn)) {
          continue;
        }

        const sourceColumnKey = this.normalizeIdentifier(sourceColumn.columnName);
        const sourceEntityToken = this.columnEntityToken(sourceColumn.columnName);

        for (const targetTable of details.tables) {
          if (targetTable.tableName === sourceTable.tableName) {
            continue;
          }

          const exactTargetColumn = targetTable.columns.find((targetColumn) =>
            this.normalizeIdentifier(targetColumn.columnName) === sourceColumnKey
            && this.isPrimaryKeyCandidate(targetTable, targetColumn)
          );
          if (exactTargetColumn) {
            addRelation(
              sourceTable,
              sourceColumn,
              targetTable,
              exactTargetColumn,
              'high',
              'Matching identifier column found on a target table.'
            );
            continue;
          }

          const targetEntityToken = this.entityToken(targetTable.tableName);
          const targetPrimaryColumn = this.primaryColumnCandidate(targetTable);
          if (sourceEntityToken && targetPrimaryColumn && this.tokensMatch(sourceEntityToken, targetEntityToken)) {
            addRelation(
              sourceTable,
              sourceColumn,
              targetTable,
              targetPrimaryColumn,
              this.isPrimaryKeyCandidate(targetTable, targetPrimaryColumn) ? 'high' : 'medium',
              'Source identifier name matches the target table entity.'
            );
          }
        }
      }
    }

    return relations.sort((left, right) =>
      `${left.sourceTable}.${left.sourceColumn}`.localeCompare(`${right.sourceTable}.${right.sourceColumn}`)
    );
  }

  private deterministicClassDiagramRelations(details: VersionDetails): ClassDiagramRelation[] {
    const availableTables = new Set(details.tables.map((table) => table.tableName));
    const relations = details.tables.flatMap((table) =>
      this.asRecordArray(table.rawAnalysis?.['deterministic_relationships'])
        .map((relation) => ({
          sourceTable: String(relation['source_table'] ?? ''),
          sourceColumn: String(relation['source_column'] ?? ''),
          targetTable: String(relation['target_table'] ?? ''),
          targetColumn: String(relation['target_column'] ?? ''),
          confidence: this.normalizedConfidence(relation['confidence']),
          reason: this.deterministicRelationReason(relation),
          coverage: Number(relation['coverage'] ?? 0),
          sourceDistinctCount: Number(relation['source_distinct_count'] ?? 0),
          targetDistinctCount: Number(relation['target_distinct_count'] ?? 0)
        }))
    );

    return relations
      .filter((relation) =>
        relation.sourceTable
        && relation.sourceColumn
        && relation.targetTable
        && relation.targetColumn
        && availableTables.has(relation.sourceTable)
        && availableTables.has(relation.targetTable)
      )
      .sort((left, right) =>
        `${left.sourceTable}.${left.sourceColumn}`.localeCompare(`${right.sourceTable}.${right.sourceColumn}`)
      );
  }

  private mergeClassDiagramRelations(
    deterministic: ClassDiagramRelation[],
    inferred: ClassDiagramRelation[]
  ): ClassDiagramRelation[] {
    const merged: ClassDiagramRelation[] = [];
    const deterministicSources = new Set(deterministic.map((relation) => `${relation.sourceTable}.${relation.sourceColumn}`));
    const seen = new Set<string>();
    for (const relation of [...deterministic, ...inferred]) {
      const sourceKey = `${relation.sourceTable}.${relation.sourceColumn}`;
      const fullKey = `${sourceKey}->${relation.targetTable}.${relation.targetColumn}`;
      if (seen.has(fullKey)) {
        continue;
      }
      if (!deterministic.includes(relation) && deterministicSources.has(sourceKey)) {
        continue;
      }
      seen.add(fullKey);
      merged.push(relation);
    }
    return merged;
  }

  private normalizedConfidence(value: unknown): 'high' | 'medium' {
    return String(value ?? '').toLowerCase() === 'high' ? 'high' : 'medium';
  }

  private deterministicRelationReason(relation: Record<string, unknown>): string {
    const reason = String(relation['reason'] ?? '').trim();
    const coverage = Number(relation['coverage'] ?? 0);
    const overlap = Number(relation['overlap_count'] ?? 0);
    if (reason) {
      return relation['sampled']
        ? `${reason} The check used capped distinct-value samples.`
        : reason;
    }
    if (coverage > 0) {
      return `${this.score(coverage * 100)}% of source values were found in the target column (${overlap} overlapping value(s)).`;
    }
    return 'Detected from deterministic value overlap before LLM enrichment.';
  }

  private dataModelGridEntities(
    entities: ClassDiagramEntity[],
    relations: ClassDiagramRelation[]
  ): ClassDiagramEntity[] {
    const degree = new Map(entities.map((entity) => [entity.tableName, 0]));
    for (const relation of relations) {
      degree.set(relation.sourceTable, (degree.get(relation.sourceTable) ?? 0) + 1);
      degree.set(relation.targetTable, (degree.get(relation.targetTable) ?? 0) + 1);
    }
    return [...entities].sort((left, right) => {
      const degreeDelta = (degree.get(right.tableName) ?? 0) - (degree.get(left.tableName) ?? 0);
      return degreeDelta || left.tableName.localeCompare(right.tableName);
    });
  }

  private dataModelLayers(
    entities: ClassDiagramEntity[],
    relations: ClassDiagramRelation[]
  ): Map<number, ClassDiagramEntity[]> {
    const layers = new Map<number, ClassDiagramEntity[]>();
    if (relations.length === 0) {
      const columnCount = Math.max(1, Math.min(entities.length || 1, Math.ceil(Math.sqrt(Math.max(1, entities.length)))));
      entities.forEach((entity, index) => {
        const layer = Math.floor(index / columnCount);
        layers.set(layer, [...(layers.get(layer) ?? []), entity]);
      });
      return layers;
    }

    const entityByName = new Map(entities.map((entity) => [entity.tableName, entity]));
    const targetsBySource = new Map<string, string[]>();
    const degreeByTable = new Map(entities.map((entity) => [entity.tableName, 0]));
    for (const relation of relations) {
      if (!entityByName.has(relation.sourceTable) || !entityByName.has(relation.targetTable)) {
        continue;
      }
      targetsBySource.set(relation.sourceTable, [...(targetsBySource.get(relation.sourceTable) ?? []), relation.targetTable]);
      degreeByTable.set(relation.sourceTable, (degreeByTable.get(relation.sourceTable) ?? 0) + 1);
      degreeByTable.set(relation.targetTable, (degreeByTable.get(relation.targetTable) ?? 0) + 1);
    }

    const memo = new Map<string, number>();
    const depthFor = (tableName: string, visiting = new Set<string>()): number => {
      if (memo.has(tableName)) {
        return memo.get(tableName) ?? 0;
      }
      if (visiting.has(tableName)) {
        return 0;
      }
      visiting.add(tableName);
      const targetDepths = (targetsBySource.get(tableName) ?? [])
        .filter((target) => entityByName.has(target))
        .map((target) => depthFor(target, visiting));
      visiting.delete(tableName);
      const depth = targetDepths.length > 0 ? Math.max(...targetDepths) + 1 : 0;
      memo.set(tableName, depth);
      return depth;
    };

    for (const entity of entities) {
      const layer = Math.min(entities.length - 1, depthFor(entity.tableName));
      layers.set(layer, [...(layers.get(layer) ?? []), entity]);
    }

    for (const [layer, layerEntities] of layers) {
      layers.set(layer, [...layerEntities].sort((left, right) => {
        const degreeDelta = (degreeByTable.get(right.tableName) ?? 0) - (degreeByTable.get(left.tableName) ?? 0);
        return degreeDelta || left.tableName.localeCompare(right.tableName);
      }));
    }
    return layers;
  }

  private groupRelationsBy(
    relations: ClassDiagramRelation[],
    keySelector: (relation: ClassDiagramRelation) => string
  ): Map<string, ClassDiagramRelation[]> {
    const groups = new Map<string, ClassDiagramRelation[]>();
    for (const relation of relations) {
      const key = keySelector(relation);
      groups.set(key, [...(groups.get(key) ?? []), relation]);
    }
    return groups;
  }

  private relationCardinality(details: VersionDetails, relation: ClassDiagramRelation): { source: string; target: string } {
    const sourceTable = details.tables.find((table) => table.tableName === relation.sourceTable);
    const targetTable = details.tables.find((table) => table.tableName === relation.targetTable);
    const sourceColumn = sourceTable?.columns.find((column) => column.columnName === relation.sourceColumn);
    const targetColumn = targetTable?.columns.find((column) => column.columnName === relation.targetColumn);
    const sourceRows = sourceTable?.rowCount ?? 0;
    const sourceMissing = sourceColumn?.missingCount ?? 0;
    const sourceNonNullRows = Math.max(0, sourceRows - sourceMissing);
    const sourceDistinct = relation.sourceDistinctCount || sourceColumn?.uniqueCount || 0;
    const targetDistinct = relation.targetDistinctCount || targetColumn?.uniqueCount || 0;
    const sourceIsUnique = sourceNonNullRows > 0 && sourceDistinct >= sourceNonNullRows * 0.98;
    const everyTargetAppears = targetDistinct > 0 && sourceDistinct >= targetDistinct;

    if (sourceIsUnique) {
      return {
        source: sourceMissing > 0 ? '0..1' : '1..1',
        target: sourceMissing > 0 ? '0..1' : '1..1'
      };
    }

    return {
      source: everyTargetAppears && sourceMissing === 0 ? '1..*' : '0..*',
      target: sourceMissing > 0 ? '0..1' : '1..1'
    };
  }

  private dataModelLaneCounts(
    relations: ClassDiagramRelation[],
    positions: Map<string, { row: number; column: number }>
  ): Map<string, number> {
    const counts = new Map<string, number>();
    for (const relation of relations) {
      const source = positions.get(relation.sourceTable);
      const target = positions.get(relation.targetTable);
      if (!source || !target) {
        continue;
      }
      const key = this.dataModelLaneKey(source.row, target.row);
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    return counts;
  }

  private dataModelLaneIndexes(
    relations: ClassDiagramRelation[],
    positions: Map<string, { row: number; column: number }>
  ): Map<number, number> {
    const counters = new Map<string, number>();
    const indexes = new Map<number, number>();
    relations.forEach((relation, index) => {
      const source = positions.get(relation.sourceTable);
      const target = positions.get(relation.targetTable);
      if (!source || !target) {
        indexes.set(index, 0);
        return;
      }
      const key = this.dataModelLaneKey(source.row, target.row);
      const next = counters.get(key) ?? 0;
      indexes.set(index, next);
      counters.set(key, next + 1);
    });
    return indexes;
  }

  private dataModelLaneKey(sourceRow: number, targetRow: number): string {
    const minRow = Math.min(sourceRow, targetRow);
    const maxRow = Math.max(sourceRow, targetRow);
    return sourceRow === targetRow ? `same-${sourceRow}` : `${minRow}-${maxRow}`;
  }

  private dataModelEdgePoints(
    source: DataModelNode,
    target: DataModelNode,
    sourceIndex: number,
    sourceCount: number,
    targetIndex: number,
    targetCount: number,
    rowTops: number[],
    rowHeights: number[],
    rowGap: number,
    laneIndex: number,
    laneCount: number
  ): {
    path: string;
    labelX: number;
    labelY: number;
    sourceCardinalityX: number;
    sourceCardinalityY: number;
    targetCardinalityX: number;
    targetCardinalityY: number;
  } {
    const sourcePortRatio = (sourceIndex + 1) / (sourceCount + 1);
    const targetPortRatio = (targetIndex + 1) / (targetCount + 1);
    const sourceColumnBeforeTarget = source.column < target.column || (source.column === target.column && sourceIndex % 2 === 0);
    const targetColumnAfterSource = sourceColumnBeforeTarget;
    const x1 = sourceColumnBeforeTarget ? source.x + source.width : source.x;
    const x2 = targetColumnAfterSource ? target.x : target.x + target.width;
    const y1 = source.y + 58 + sourcePortRatio * Math.max(24, source.height - 76);
    const y2 = target.y + 58 + targetPortRatio * Math.max(24, target.height - 76);
    const sourceSideOffset = 24 + sourceIndex * 12 + laneIndex * 3;
    const targetSideOffset = 24 + targetIndex * 12 + laneIndex * 3;
    const sourceGutterX = sourceColumnBeforeTarget ? source.x + source.width + sourceSideOffset : source.x - sourceSideOffset;
    const targetGutterX = targetColumnAfterSource ? target.x - targetSideOffset : target.x + target.width + targetSideOffset;
    const minRow = Math.min(source.row, target.row);
    const maxRow = Math.max(source.row, target.row);
    const safeLaneCount = Math.max(1, laneCount);
    const laneStep = Math.min(34, Math.max(20, (rowGap - 38) / safeLaneCount));
    const laneOffset = 22 + laneIndex * laneStep;
    let laneY: number;
    if (source.row === target.row) {
      const rowBottom = rowTops[source.row] + rowHeights[source.row];
      laneY = rowBottom + Math.min(rowGap - 16, laneOffset);
    } else {
      const upperRowBottom = rowTops[minRow] + rowHeights[minRow];
      const lowerRowTop = rowTops[maxRow];
      laneY = upperRowBottom + Math.min(Math.max(18, lowerRowTop - upperRowBottom - 18), laneOffset);
    }
    const labelX = (sourceGutterX + targetGutterX) / 2;
    return {
      path: `M ${x1} ${y1} L ${sourceGutterX} ${y1} L ${sourceGutterX} ${laneY} L ${targetGutterX} ${laneY} L ${targetGutterX} ${y2} L ${x2} ${y2}`,
      labelX,
      labelY: laneY - 8,
      sourceCardinalityX: sourceColumnBeforeTarget ? x1 + 20 : x1 - 20,
      sourceCardinalityY: y1 - 7,
      targetCardinalityX: targetColumnAfterSource ? x2 - 20 : x2 + 20,
      targetCardinalityY: y2 - 7
    };
  }

  private dataModelSvg(details: VersionDetails): string {
    const model = this.dataModelMap(details);
    const edgePathLines = model.edges.map((edge) => `
      <path d="${this.xmlEscape(edge.path)}" fill="none" stroke="#2f766b" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" marker-end="url(#arrowHead)">
        <title>${this.xmlEscape(edge.reason)}</title>
      </path>
    `).join('');
    const edgeLabelLines = model.edges.map((edge) => `
      <rect x="${edge.labelX - edge.labelWidth / 2}" y="${edge.labelY - 14}" width="${edge.labelWidth}" height="18" rx="9" fill="#f8fbfa" stroke="#c8d8d3"/>
      <text x="${edge.labelX}" y="${edge.labelY}" text-anchor="middle" font-size="11" font-weight="800" fill="#24594f">${this.xmlEscape(edge.label)}</text>
      <rect x="${edge.sourceCardinalityX - 18}" y="${edge.sourceCardinalityY - 14}" width="36" height="18" rx="9" fill="#ffffff" stroke="#bdd0ca"/>
      <text x="${edge.sourceCardinalityX}" y="${edge.sourceCardinalityY}" text-anchor="middle" font-size="11" font-weight="900" fill="#1f5e55">${this.xmlEscape(edge.sourceCardinality)}</text>
      <rect x="${edge.targetCardinalityX - 18}" y="${edge.targetCardinalityY - 14}" width="36" height="18" rx="9" fill="#ffffff" stroke="#bdd0ca"/>
      <text x="${edge.targetCardinalityX}" y="${edge.targetCardinalityY}" text-anchor="middle" font-size="11" font-weight="900" fill="#1f5e55">${this.xmlEscape(edge.targetCardinality)}</text>
    `).join('');
    const nodeLines = model.nodes.map((node) => {
      const fields = node.fields.map((field) => `
        <text x="14" y="${field.y}" font-size="11" fill="#3f554e">
          <tspan font-weight="800">${this.xmlEscape(field.role)}</tspan>
          <tspan> ${this.xmlEscape(field.name)}</tspan>
          <tspan fill="#71847e"> ${this.xmlEscape(field.type)}</tspan>
        </text>
      `).join('');
      const more = node.moreCount > 0
        ? `<text x="14" y="164" font-size="11" font-weight="700" fill="#60736d">+ ${node.moreCount} more field(s)</text>`
        : '';
      return `
        <g transform="translate(${node.x}, ${node.y})">
          <rect width="${node.width}" height="${node.height}" rx="8" fill="#ffffff" stroke="#b7c9c3" stroke-width="1.4"/>
          <rect width="${node.width}" height="42" rx="8" fill="#eaf3f0"/>
          <text x="14" y="25" font-size="14" font-weight="800" fill="#203c35">${this.xmlEscape(node.label)}</text>
          <text x="14" y="51" font-size="10" font-weight="700" fill="#60736d">${this.xmlEscape(node.subtitle)}</text>
          ${fields}
          ${more}
        </g>
      `;
    }).join('');
    return `<svg xmlns="http://www.w3.org/2000/svg" width="${model.width}" height="${model.height}" viewBox="0 0 ${model.width} ${model.height}">
      <rect width="100%" height="100%" fill="#f8fbfa"/>
      <defs>
        <marker id="arrowHead" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" fill="#2f766b"/>
        </marker>
      </defs>
      ${edgePathLines}
      ${nodeLines}
      ${edgeLabelLines}
    </svg>`;
  }

  private compactText(value: string, maxLength: number): string {
    return value.length > maxLength ? `${value.slice(0, Math.max(1, maxLength - 1))}...` : value;
  }

  private edgeLabelWidth(value: string): number {
    return Math.max(58, Math.min(140, this.compactText(value, 18).length * 7 + 18));
  }

  private xmlEscape(value: string): string {
    return value
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&apos;');
  }

  private classDiagramColumnRole(
    table: TableAnalysisView,
    column: ColumnMetricView,
    foreignKeys: Set<string>
  ): string {
    const isPk = this.isPrimaryKeyCandidate(table, column);
    const isFk = foreignKeys.has(`${table.tableName}.${column.columnName}`);
    if (isPk && isFk) {
      return 'PK_FK';
    }
    if (isPk) {
      return 'PK';
    }
    if (isFk) {
      return 'FK';
    }
    if (this.isIdentifierColumn(column.columnName)) {
      return 'ID';
    }
    if (this.normalizeType(column.dataType) === 'Date') {
      return 'DATE';
    }
    if (this.isNumericType(column.dataType ?? '')) {
      return 'NUM';
    }
    return 'ATTR';
  }

  private primaryColumnCandidate(table: TableAnalysisView): ColumnMetricView | undefined {
    return table.columns.find((column) => this.isPrimaryKeyCandidate(table, column))
      ?? table.columns.find((column) => this.isIdentifierColumn(column.columnName));
  }

  private isPrimaryKeyCandidate(table: TableAnalysisView, column: ColumnMetricView): boolean {
    const columnKey = this.normalizeIdentifier(column.columnName);
    const tableToken = this.entityToken(table.tableName);
    const columnToken = this.columnEntityToken(column.columnName);
    const rowCount = table.rowCount ?? 0;
    const uniqueRatio = rowCount > 0 ? (column.uniqueCount ?? 0) / rowCount : 0;
    const idColumns = table.columns.filter((candidate) => this.isIdentifierColumn(candidate.columnName));

    return columnKey === 'id'
      || columnToken === tableToken
      || (idColumns.length === 1 && this.isIdentifierColumn(column.columnName) && uniqueRatio >= 0.98 && (column.missingCount ?? 0) === 0);
  }

  private isOwnIdentifier(table: TableAnalysisView, column: ColumnMetricView): boolean {
    const token = this.columnEntityToken(column.columnName);
    return !!token && token === this.entityToken(table.tableName);
  }

  private isIdentifierColumn(columnName: string): boolean {
    const key = this.normalizeIdentifier(columnName);
    return key === 'id'
      || key.endsWith('_id')
      || key.endsWith('_key')
      || key.endsWith('_code')
      || key.includes('identifier');
  }

  private entityToken(tableName: string): string {
    return this.singularIdentifier(this.normalizeIdentifier(tableName));
  }

  private columnEntityToken(columnName: string): string {
    const withoutSuffix = this.normalizeIdentifier(columnName)
      .replace(/_(identifier|id|key|code)$/, '')
      .replace(/^(identifier|id|key|code)_/, '');
    return this.singularIdentifier(withoutSuffix);
  }

  private tokensMatch(left: string, right: string): boolean {
    return left === right || left.endsWith(`_${right}`) || right.endsWith(`_${left}`);
  }

  private normalizeIdentifier(value: string): string {
    return value
      .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')
      .replace(/^_+|_+$/g, '');
  }

  private singularIdentifier(identifier: string): string {
    return identifier
      .split('_')
      .map((part) => {
        if (part.length > 3 && part.endsWith('ies')) {
          return `${part.slice(0, -3)}y`;
        }
        if (part.length > 3 && part.endsWith('s') && !part.endsWith('ss')) {
          return part.slice(0, -1);
        }
        return part;
      })
      .join('_');
  }

  private safeFileName(value: string): string {
    return this.normalizeIdentifier(value).replace(/_+/g, '-') || 'dataset';
  }

  private asRecord(value: unknown): Record<string, unknown> {
    return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
  }

  private asRecordArray(value: unknown): Record<string, unknown>[] {
    return Array.isArray(value) ? value.filter((item) => item && typeof item === 'object') as Record<string, unknown>[] : [];
  }

  private isMeaningfulGlobalText(value?: string): boolean {
    const text = (value ?? '').trim();
    return text.length > 0
      && !text.startsWith('Processed ')
      && text !== 'No final assessment returned yet.';
  }

  private sum(values: number[]): number {
    return values.reduce((total, value) => total + value, 0);
  }

  private humanizeKey(key: string): string {
    return key
      .replace(/_/g, ' ')
      .replace(/\b\w/g, (letter) => letter.toUpperCase());
  }

  private normalizeType(value?: string): string {
    const lower = (value ?? 'unknown').toLowerCase();
    if (this.isNumericType(lower)) {
      return 'Numerical';
    }
    if (lower.includes('date') || lower.includes('time')) {
      return 'Date';
    }
    if (lower.includes('text') || lower.includes('string') || lower.includes('object')) {
      return 'Categorical';
    }
    return value || 'Unknown';
  }

  private isNumericType(value: string): boolean {
    const lower = value.toLowerCase();
    return lower.includes('int') || lower.includes('float') || lower.includes('double') || lower.includes('decimal') || lower.includes('number') || lower.includes('numeric');
  }
}
