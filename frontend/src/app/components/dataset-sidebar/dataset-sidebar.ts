import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { DatasetSummary } from '../../models/data-quality.models';

@Component({
  selector: 'app-dataset-sidebar',
  imports: [CommonModule],
  templateUrl: './dataset-sidebar.html',
  styleUrl: './dataset-sidebar.css',
})
export class DatasetSidebar {
  @Input() datasets: DatasetSummary[] = [];
  @Input() selectedDatasetId: number | null = null;
  @Input() loading = false;
  @Input() deletingDatasetId: number | null = null;

  @Output() datasetSelected = new EventEmitter<DatasetSummary>();
  @Output() datasetDeleted = new EventEmitter<{ dataset: DatasetSummary; event: Event }>();

  statusClass(status?: string): string {
    return (status ?? 'UNKNOWN').toLowerCase();
  }
}
