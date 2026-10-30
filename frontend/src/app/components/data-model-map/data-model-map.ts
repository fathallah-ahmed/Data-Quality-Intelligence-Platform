import { CommonModule, DecimalPipe } from '@angular/common';
import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import {
  ClassDiagramEntity,
  ClassDiagramRelation,
  DataModelEdge,
  DataModelMap,
  DataModelNode,
  VersionDetails
} from '../../models/data-quality.models';

@Component({
  selector: 'app-data-model-map',
  imports: [CommonModule, DecimalPipe],
  templateUrl: './data-model-map.html',
  styleUrl: './data-model-map.css',
})
export class DataModelMapComponent {
  @Input() details: VersionDetails | null = null;
  @Input() scopeLabel = 'Data model map';
  @Input() entities: ClassDiagramEntity[] = [];
  @Input() relations: ClassDiagramRelation[] = [];
  @Input() model: DataModelMap | null = null;

  @Output() downloadRequested = new EventEmitter<VersionDetails>();

  dataModelZoom = signal(100);
  showDataModelRelations = signal(true);
  hoveredDataModelEntity = signal<string | null>(null);

  zoomDataModel(delta: number): void {
    this.dataModelZoom.update((value) => Math.max(50, Math.min(180, value + delta)));
  }

  resetDataModelZoom(): void {
    this.dataModelZoom.set(100);
  }

  toggleDataModelRelations(): void {
    this.showDataModelRelations.update((value) => !value);
  }

  hoverDataModelEntity(entityId: string | null): void {
    this.hoveredDataModelEntity.set(entityId);
  }

  isDataModelEdgeActive(edge: DataModelEdge): boolean {
    const hovered = this.hoveredDataModelEntity();
    return !hovered || edge.sourceTable === hovered || edge.targetTable === hovered;
  }

  dataModelEdgeOpacity(edge: DataModelEdge): number {
    return this.isDataModelEdgeActive(edge) ? 0.92 : 0.12;
  }

  dataModelEdgeStroke(edge: DataModelEdge): number {
    return this.isDataModelEdgeActive(edge) ? 2.7 : 1.4;
  }

  dataModelLabelOpacity(edge: DataModelEdge): number {
    return this.isDataModelEdgeActive(edge) ? 1 : 0.18;
  }

  dataModelNodeOpacity(node: DataModelNode): number {
    const hovered = this.hoveredDataModelEntity();
    if (!hovered) {
      return 1;
    }
    const isRelated = this.model?.edges.some((edge) =>
      (edge.sourceTable === hovered && edge.targetTable === node.id)
      || (edge.targetTable === hovered && edge.sourceTable === node.id)
    );
    return node.id === hovered || isRelated ? 1 : 0.35;
  }

  isDataModelNodeHovered(node: DataModelNode): boolean {
    return this.hoveredDataModelEntity() === node.id;
  }
}
