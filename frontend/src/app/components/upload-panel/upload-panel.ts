import { CommonModule } from '@angular/common';
import { Component, ElementRef, EventEmitter, Input, OnChanges, Output, SimpleChanges, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ContextScope, UploadResponse } from '../../models/data-quality.models';

@Component({
  selector: 'app-upload-panel',
  imports: [CommonModule, FormsModule],
  templateUrl: './upload-panel.html',
  styleUrl: './upload-panel.css',
})
export class UploadPanel implements OnChanges {
  @ViewChild('fileInput') private fileInput?: ElementRef<HTMLInputElement>;

  @Input() datasetName = '';
  @Input() selectedFiles: File[] = [];
  @Input() contextScope: ContextScope = 'none';
  @Input() showUploadContext = false;
  @Input() globalDescription = '';
  @Input() globalQuestion = '';
  @Input() tableDescriptions: Record<string, string> = {};
  @Input() uploading = false;
  @Input() duplicatePrompt: UploadResponse | null = null;

  @Output() datasetNameChange = new EventEmitter<string>();
  @Output() filesSelected = new EventEmitter<File[]>();
  @Output() uploadRequested = new EventEmitter<void>();
  @Output() contextToggled = new EventEmitter<void>();
  @Output() contextScopeChange = new EventEmitter<ContextScope>();
  @Output() globalDescriptionChange = new EventEmitter<string>();
  @Output() globalQuestionChange = new EventEmitter<string>();
  @Output() tableDescriptionChanged = new EventEmitter<{ file: File; value: string }>();
  @Output() fileRemoved = new EventEmitter<File>();
  @Output() duplicateOpened = new EventEmitter<void>();
  @Output() duplicateRunAgain = new EventEmitter<void>();
  @Output() duplicateDismissed = new EventEmitter<void>();

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['selectedFiles'] && this.selectedFiles.length === 0 && this.fileInput?.nativeElement) {
      this.fileInput.nativeElement.value = '';
    }
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.filesSelected.emit(Array.from(input.files ?? []));
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

  fileSizeLabel(file: File): string {
    if (file.size < 1024) {
      return `${file.size} B`;
    }
    if (file.size < 1024 * 1024) {
      return `${Math.round(file.size / 1024)} KB`;
    }
    return `${(file.size / (1024 * 1024)).toFixed(1)} MB`;
  }

  selectedFileSummary(): string {
    if (this.selectedFiles.length === 0) {
      return 'No CSV selected';
    }
    const names = this.selectedFiles.slice(0, 2).map((file) => file.name);
    const remaining = this.selectedFiles.length - names.length;
    return remaining > 0 ? `${names.join(', ')} +${remaining} more` : names.join(', ');
  }
}
