import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { environment } from '../../../environments/environment';
import { ApiService, apiErrorMessage } from '../../core/api.service';
import { LayoutService } from '../../core/layout.service';
import { ContextScope, UploadResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { WorkspaceStore } from '../../core/workspace.store';
import { Modal } from '../../shared/components';
import { formatBytes } from '../../shared/format';
import { Icon } from '../../shared/icon';
import { FileProblem, mergeSelection, tableNameFromFile } from './upload-validation';

@Component({
  selector: 'app-upload-dialog',
  imports: [Modal, FormsModule, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './upload-dialog.html',
  styleUrl: './upload-dialog.css'
})
export class UploadDialog {
  readonly layout = inject(LayoutService);
  private readonly api = inject(ApiService);
  private readonly store = inject(WorkspaceStore);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);
  readonly maxBytes = environment.maxUploadBytes;
  readonly maxLabel = formatBytes(environment.maxUploadBytes);

  readonly files = signal<File[]>([]);
  readonly problems = signal<FileProblem[]>([]);
  readonly datasetName = signal('');
  readonly showContext = signal(false);
  readonly contextScope = signal<ContextScope>('none');
  readonly globalDescription = signal('');
  readonly globalQuestion = signal('');
  readonly tableDescriptions = signal<Record<string, string>>({});
  readonly dragging = signal(false);
  readonly uploading = signal(false);
  readonly error = signal<string | null>(null);
  readonly duplicate = signal<UploadResponse | null>(null);

  readonly totalSize = computed(() => formatBytes(this.files().reduce((sum, file) => sum + file.size, 0)));
  readonly namePlaceholder = computed(() => (this.files()[0] ? tableNameFromFile(this.files()[0]) : 'e.g. sales-2026'));
  readonly usesGlobal = computed(() => this.contextScope() === 'global' || this.contextScope() === 'both');
  readonly usesTables = computed(() => this.contextScope() === 'per_table' || this.contextScope() === 'both');
  readonly title = computed(() => (this.duplicate() ? 'These files were already analysed' : 'New analysis'));

  addFiles(list: FileList | null): void {
    if (!list || list.length === 0) {
      return;
    }
    const { files, problems } = mergeSelection(this.files(), Array.from(list), this.maxBytes);
    this.files.set(files);
    this.problems.set(problems);
    this.error.set(null);
  }

  onInputChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.addFiles(input.files);
    input.value = '';
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    this.addFiles(event.dataTransfer?.files ?? null);
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  removeFile(file: File): void {
    this.files.update((files) => files.filter((item) => item !== file));
  }

  size(file: File): string {
    return formatBytes(file.size);
  }

  tableName(file: File): string {
    return tableNameFromFile(file);
  }

  setTableDescription(file: File, value: string): void {
    this.tableDescriptions.update((descriptions) => ({ ...descriptions, [tableNameFromFile(file)]: value }));
  }

  submit(rerunExisting = false): void {
    if (this.files().length === 0 || this.uploading()) {
      this.error.set('Add at least one CSV file.');
      return;
    }
    const context = this.showContext();
    const descriptions = Object.fromEntries(
      this.files()
        .map((file) => [tableNameFromFile(file), (this.tableDescriptions()[tableNameFromFile(file)] ?? '').trim()])
        .filter(([, value]) => value)
    );
    this.uploading.set(true);
    this.error.set(null);
    this.api.upload({
      files: this.files(),
      name: this.datasetName(),
      contextScope: context ? this.contextScope() : 'none',
      globalDescription: context && this.usesGlobal() ? this.globalDescription() : undefined,
      globalQuestion: context ? this.globalQuestion() : undefined,
      tableDescriptions: context && this.usesTables() ? descriptions : undefined
    }, rerunExisting).subscribe({
      next: (response) => {
        this.uploading.set(false);
        if (response.duplicateVersion && !response.rerunStarted && !rerunExisting) {
          this.duplicate.set(response);
          return;
        }
        this.finish(response);
      },
      error: (error) => {
        this.uploading.set(false);
        this.error.set(apiErrorMessage(error, 'The upload failed.'));
      }
    });
  }

  openExisting(): void {
    const duplicate = this.duplicate();
    if (duplicate) {
      this.navigateTo(duplicate);
      this.close(true);
    }
  }

  close(reset = false): void {
    if (this.uploading()) {
      return;
    }
    this.layout.uploadOpen.set(false);
    this.duplicate.set(null);
    this.error.set(null);
    if (reset) {
      this.reset();
    }
  }

  private finish(response: UploadResponse): void {
    if (!response.airflowTriggered) {
      this.toasts.error(`Files saved as version ${response.versionNumber}, but the analysis could not start. ${response.airflowMessage}`);
    } else if (response.rerunStarted) {
      this.toasts.success(`Version ${response.versionNumber} is running again.`);
    } else {
      this.toasts.success(`Version ${response.versionNumber} uploaded (${response.fileCount} table(s)). The analysis has started.`);
    }
    if (response.skippedFiles?.length) {
      this.toasts.warning(`Skipped: ${response.skippedFiles.join(', ')}`);
    }
    this.store.loadDatasets();
    this.navigateTo(response);
    this.close(true);
  }

  private navigateTo(response: UploadResponse): void {
    this.store.refresh();
    this.router.navigate(['/datasets', response.datasetId, 'versions', response.versionId, 'overview']);
  }

  private reset(): void {
    this.files.set([]);
    this.problems.set([]);
    this.datasetName.set('');
    this.showContext.set(false);
    this.contextScope.set('none');
    this.globalDescription.set('');
    this.globalQuestion.set('');
    this.tableDescriptions.set({});
  }
}
