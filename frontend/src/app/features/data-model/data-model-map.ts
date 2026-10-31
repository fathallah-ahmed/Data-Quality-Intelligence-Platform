import { ChangeDetectionStrategy, Component, ElementRef, computed, input, output, signal, viewChild } from '@angular/core';
import { DataModelEdge, DataModelMap, DataModelNode } from '../../core/models';
import { Icon } from '../../shared/icon';

/** Interactive SVG data model: zoom, drag to pan, hover/focus a table to highlight its relations. */
@Component({
  selector: 'app-data-model-map',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './data-model-map.html',
  styleUrl: './data-model-map.css'
})
export class DataModelMapComponent {
  readonly model = input.required<DataModelMap>();
  readonly label = input('Data model diagram');
  readonly downloadRequested = output<void>();

  readonly zoom = signal(100);
  readonly showRelations = signal(true);
  readonly hovered = signal<string | null>(null);
  readonly panning = signal(false);
  private readonly viewport = viewChild.required<ElementRef<HTMLElement>>('viewport');
  private dragStart: { x: number; y: number; left: number; top: number } | null = null;

  readonly relatedNodes = computed(() => {
    const hovered = this.hovered();
    if (!hovered) {
      return null;
    }
    const related = new Set<string>([hovered]);
    for (const edge of this.model().edges) {
      if (edge.sourceTable === hovered) {
        related.add(edge.targetTable);
      }
      if (edge.targetTable === hovered) {
        related.add(edge.sourceTable);
      }
    }
    return related;
  });

  zoomBy(delta: number): void {
    this.zoom.update((value) => Math.max(40, Math.min(200, value + delta)));
  }

  reset(): void {
    this.zoom.set(100);
    const element = this.viewport().nativeElement;
    element.scrollTo({ left: 0, top: 0 });
  }

  isEdgeActive(edge: DataModelEdge): boolean {
    const hovered = this.hovered();
    return !hovered || edge.sourceTable === hovered || edge.targetTable === hovered;
  }

  isNodeDimmed(node: DataModelNode): boolean {
    const related = this.relatedNodes();
    return !!related && !related.has(node.id);
  }

  onPointerDown(event: PointerEvent): void {
    if (event.button !== 0) {
      return;
    }
    const element = this.viewport().nativeElement;
    this.dragStart = { x: event.clientX, y: event.clientY, left: element.scrollLeft, top: element.scrollTop };
    this.panning.set(true);
    element.setPointerCapture?.(event.pointerId);
  }

  onPointerMove(event: PointerEvent): void {
    if (!this.dragStart) {
      return;
    }
    const element = this.viewport().nativeElement;
    element.scrollLeft = this.dragStart.left - (event.clientX - this.dragStart.x);
    element.scrollTop = this.dragStart.top - (event.clientY - this.dragStart.y);
  }

  onPointerUp(): void {
    this.dragStart = null;
    this.panning.set(false);
  }

  onWheel(event: WheelEvent): void {
    if (event.ctrlKey || event.metaKey) {
      event.preventDefault();
      this.zoomBy(event.deltaY > 0 ? -10 : 10);
    }
  }
}
