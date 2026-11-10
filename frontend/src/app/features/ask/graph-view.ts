import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { ApiService, apiErrorMessage } from '../../core/api.service';
import { Subgraph, SubgraphEdge } from '../../core/models';
import { PlacedNode, labelColour, layoutStep, mergeEdges, mergeNodes } from './graph-layout';

/** A property value as text (objects and lists as JSON). */
function cellText(value: unknown): string {
  if (value === null || value === undefined) {
    return '';
  }
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

const WIDTH = 760;
const HEIGHT = 440;
const RADIUS = 15;
const MAX_FRAMES = 260;

interface Drag {
  kind: 'node' | 'pan';
  id?: string;
  startX: number;
  startY: number;
  originX: number;
  originY: number;
  moved: boolean;
}

/**
 * The sub-graph behind an answer, drawn and explorable: drag a node, move and zoom the drawing, click a node to read its
 * properties, expand it (button or double click) to bring in its neighbours.
 */
@Component({
  selector: 'app-graph-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="legend" aria-label="Kinds of node">
      @for (label of labels(); track label) {
        <span class="legend__item"><span class="legend__dot" [style.background]="colour(label)"></span>{{ label }}</span>
      }
      <span class="legend__item"><span class="legend__dot legend__dot--conflict"></span>conflict</span>
      <span class="legend__count">{{ nodes().length }} nodes · {{ edges().length }} relationships</span>
    </div>
    <div class="stage">
      <svg
        #svg
        class="canvas"
        [attr.viewBox]="'0 0 ' + width + ' ' + height"
        role="img"
        aria-label="Graph behind the answer. Drag to move, scroll to zoom, click a node for its properties."
        (pointerdown)="startPan($event)"
        (pointermove)="move($event)"
        (pointerup)="end($event)"
        (pointercancel)="end($event)"
        (wheel)="zoom($event)"
      >
        <defs>
          <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
            <path d="M0 0 L10 5 L0 10 z" class="arrow" />
          </marker>
        </defs>
        <g [attr.transform]="transform()">
          @for (edge of lines(); track edge.id) {
            <g class="edge" [class.edge--on]="edge.active">
              <line [attr.x1]="edge.x1" [attr.y1]="edge.y1" [attr.x2]="edge.x2" [attr.y2]="edge.y2" marker-end="url(#arrow)" />
              <text [attr.x]="(edge.x1 + edge.x2) / 2" [attr.y]="(edge.y1 + edge.y2) / 2 - 3" text-anchor="middle">{{ edge.type }}</text>
            </g>
          }
          @for (node of nodes(); track node.id) {
            <g
              class="node"
              [class.node--selected]="node.id === selectedId()"
              [attr.transform]="'translate(' + node.x + ',' + node.y + ')'"
              tabindex="0"
              role="button"
              [attr.aria-label]="node.label + ' ' + node.caption"
              (pointerdown)="startNode($event, node)"
              (keydown.enter)="select(node.id)"
            >
              <circle [attr.r]="radius" [attr.fill]="colour(node.label)" [class.conflict]="node.conflict" />
              <text class="node__caption" y="28" text-anchor="middle">{{ short(node.caption) }}</text>
            </g>
          }
        </g>
      </svg>
      <div class="tools">
        <button type="button" class="tool" (click)="resetView()" aria-label="Fit the drawing">Fit</button>
        <button type="button" class="tool" (click)="relayout()" aria-label="Rearrange the nodes">Rearrange</button>
      </div>
    </div>
    @if (truncated()) {
      <p class="note">Only part of the graph is drawn (the size is limited). Expand a node to see more around it.</p>
    }
    @if (message()) {
      <p class="note" role="status">{{ message() }}</p>
    }
    @if (selected(); as node) {
      <div class="panel">
        <div class="panel__head">
          <strong>{{ node.label }}</strong> <span class="panel__key">{{ node.caption }}</span>
          <button type="button" class="tool" [disabled]="expanding()" (click)="expand(node)">{{ expanding() ? 'Loading…' : 'Expand neighbours' }}</button>
        </div>
        @if (node.conflict) {
          <p class="panel__conflict">This node has a property with several candidate values: the data disagrees with itself.</p>
        }
        <dl class="props">
          @for (entry of properties(); track entry[0]) {
            <dt>{{ entry[0] }}</dt>
            <dd>{{ cell(entry[1]) }}</dd>
          }
        </dl>
      </div>
    } @else {
      <p class="note">Click a node to read its properties; double-click it, or use “Expand neighbours”, to bring in what is linked to it.</p>
    }
  `,
  styles: `
    :host { display: grid; gap: var(--space-2); min-width: 0; }
    .legend { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-3); font-size: var(--text-xs); color: var(--text-muted); }
    .legend__item { display: inline-flex; align-items: center; gap: var(--space-1); }
    .legend__dot { width: 10px; height: 10px; border-radius: 50%; }
    .legend__dot--conflict { background: transparent; border: 2px solid var(--danger); }
    .legend__count { margin-left: auto; }
    .stage { position: relative; border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--surface-sunken); overflow: hidden; }
    .canvas { display: block; width: 100%; height: auto; max-height: 60vh; touch-action: none; cursor: grab; user-select: none; }
    .canvas:active { cursor: grabbing; }
    .tools { position: absolute; top: var(--space-2); right: var(--space-2); display: flex; gap: var(--space-1); }
    .tool { padding: 2px var(--space-2); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--surface); color: var(--text); font-size: var(--text-xs); cursor: pointer; }
    .tool:hover:not(:disabled), .tool:focus-visible { border-color: var(--primary); }
    .tool:disabled { opacity: 0.6; cursor: default; }
    .edge line { stroke: var(--border-strong); stroke-width: 1.4; }
    .edge text { fill: var(--text-subtle); font-size: 9px; pointer-events: none; }
    .edge--on line { stroke: var(--primary); stroke-width: 2; }
    .edge--on text { fill: var(--primary); }
    .arrow { fill: var(--border-strong); }
    .node { cursor: pointer; outline: none; }
    .node circle { stroke: var(--surface); stroke-width: 2; }
    .node circle.conflict { stroke: var(--danger); stroke-width: 3.5; }
    .node--selected circle { stroke: var(--text); stroke-width: 3; }
    .node:focus-visible circle { stroke: var(--focus); stroke-width: 3.5; }
    .node__caption { fill: var(--text); font-size: 10px; pointer-events: none; paint-order: stroke; stroke: var(--surface-sunken); stroke-width: 3px; }
    .note { margin: 0; color: var(--text-muted); font-size: var(--text-xs); }
    .panel { border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--surface); padding: var(--space-3); display: grid; gap: var(--space-2); }
    .panel__head { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2); }
    .panel__head .tool { margin-left: auto; }
    .panel__key { color: var(--text-muted); font-family: var(--font-mono); font-size: var(--text-sm); }
    .panel__conflict { margin: 0; color: var(--danger); font-size: var(--text-sm); }
    .props { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 2px var(--space-3); margin: 0; font-size: var(--text-sm); }
    .props dt { color: var(--text-muted); }
    .props dd { margin: 0; overflow-wrap: anywhere; }
  `
})
export class GraphView {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);

  readonly datasetId = input.required<number>();
  readonly versionId = input.required<number>();
  readonly graph = input.required<Subgraph>();

  readonly width = WIDTH;
  readonly height = HEIGHT;
  readonly radius = RADIUS;
  readonly cell = cellText;

  readonly nodes = signal<PlacedNode[]>([]);
  readonly edges = signal<SubgraphEdge[]>([]);
  readonly selectedId = signal<string | null>(null);
  readonly expanding = signal(false);
  readonly message = signal('');
  readonly truncated = signal(false);
  private readonly view = signal({ x: 0, y: 0, k: 1 });

  readonly transform = computed(() => `translate(${this.view().x},${this.view().y}) scale(${this.view().k})`);
  readonly labels = computed(() => [...new Set(this.nodes().map((node) => node.label))].sort());
  readonly selected = computed(() => this.nodes().find((node) => node.id === this.selectedId()) ?? null);
  readonly properties = computed(() => Object.entries(this.selected()?.properties ?? {}).filter(([key]) => !key.startsWith('_') || key === '_conflicts'));
  readonly lines = computed(() => {
    const byId = new Map(this.nodes().map((node) => [node.id, node]));
    const selected = this.selectedId();
    return this.edges().flatMap((edge) => {
      const a = byId.get(edge.from);
      const b = byId.get(edge.to);
      if (!a || !b) {
        return [];
      }
      // The arrow stops at the edge of the circle, not at its centre.
      const dx = b.x - a.x;
      const dy = b.y - a.y;
      const length = Math.sqrt(dx * dx + dy * dy) || 1;
      return [{
        id: edge.id,
        type: edge.type,
        x1: a.x,
        y1: a.y,
        x2: b.x - (dx / length) * (RADIUS + 3),
        y2: b.y - (dy / length) * (RADIUS + 3),
        active: edge.from === selected || edge.to === selected
      }];
    });
  });

  private drag: Drag | null = null;
  /** The last click on a node, to tell a double click: the svg holds the pointer while a node is pressed, so the browser's own dblclick never reaches the node. */
  private lastClick = { id: '', at: 0 };
  private frame = 0;
  private frames = 0;

  constructor() {
    effect(() => {
      const graph = this.graph();
      untracked(() => this.load(graph));
    });
    this.destroyRef.onDestroy(() => cancelAnimationFrame(this.frame));
  }

  colour(label: string): string {
    return labelColour(this.labels(), label);
  }

  short(text: string): string {
    return text.length > 22 ? `${text.slice(0, 21)}…` : text;
  }

  // ------------------------------------------------------------------ content

  private load(graph: Subgraph): void {
    const nodes = mergeNodes([], graph.nodes, graph.edges, WIDTH, HEIGHT);
    this.nodes.set(nodes);
    this.edges.set(mergeEdges([], graph.edges, nodes));
    this.truncated.set(graph.truncated);
    this.selectedId.set(null);
    this.message.set('');
    this.view.set({ x: 0, y: 0, k: 1 });
    this.settle(true);
  }

  select(id: string): void {
    this.selectedId.set(id);
  }

  expand(node: PlacedNode): void {
    this.selectedId.set(node.id);
    if (this.expanding()) {
      return;
    }
    this.expanding.set(true);
    this.message.set('');
    this.api.expandNode(this.datasetId(), this.versionId(), node.id).subscribe({
      next: (more) => {
        const before = this.nodes().length;
        const nodes = mergeNodes(this.nodes(), more.nodes, more.edges, WIDTH, HEIGHT);
        const edges = mergeEdges(this.edges(), more.edges, nodes);
        this.nodes.set(nodes);
        this.edges.set(edges);
        this.expanding.set(false);
        const added = nodes.length - before;
        this.message.set(added ? `${added} node${added > 1 ? 's' : ''} added around ${node.caption}.${more.truncated ? ' It has more neighbours than shown.' : ''}` : `No new neighbour for ${node.caption}.`);
        this.settle(false);
      },
      error: (error) => {
        this.expanding.set(false);
        this.message.set(apiErrorMessage(error, 'The neighbours could not be loaded.'));
      }
    });
  }

  // ------------------------------------------------------------------ layout

  /** Runs the layout for a while. At the start the first frames are done at once, so the drawing does not open in a heap. */
  private settle(immediate: boolean): void {
    cancelAnimationFrame(this.frame);
    this.frames = 0;
    if (immediate) {
      for (let i = 0; i < 120; i++) {
        layoutStep(this.nodes(), this.edges(), WIDTH, HEIGHT);
      }
      this.nodes.set([...this.nodes()]);
    }
    this.run();
  }

  private run = (): void => {
    const fastest = layoutStep(this.nodes(), this.edges(), WIDTH, HEIGHT);
    this.nodes.set([...this.nodes()]);
    this.frames += 1;
    if (fastest > 0.05 && this.frames < MAX_FRAMES) {
      this.frame = requestAnimationFrame(this.run);
    }
  };

  relayout(): void {
    this.nodes().forEach((node) => (node.pinned = false));
    this.settle(false);
  }

  resetView(): void {
    this.view.set({ x: 0, y: 0, k: 1 });
  }

  // ------------------------------------------------------------------ pointer

  /** Size of one screen pixel in the units of the drawing. */
  private scale(event: PointerEvent | WheelEvent): number {
    const svg = (event.currentTarget as Element).closest('svg') as SVGSVGElement;
    return WIDTH / (svg.getBoundingClientRect().width || WIDTH);
  }

  startPan(event: PointerEvent): void {
    this.drag = { kind: 'pan', startX: event.clientX, startY: event.clientY, originX: this.view().x, originY: this.view().y, moved: false };
    (event.currentTarget as Element).setPointerCapture?.(event.pointerId);
  }

  startNode(event: PointerEvent, node: PlacedNode): void {
    event.stopPropagation();
    this.drag = { kind: 'node', id: node.id, startX: event.clientX, startY: event.clientY, originX: node.x, originY: node.y, moved: false };
    node.pinned = true;
    (event.currentTarget as Element).closest('svg')?.setPointerCapture?.(event.pointerId);
  }

  move(event: PointerEvent): void {
    const drag = this.drag;
    if (!drag) {
      return;
    }
    const factor = this.scale(event);
    const dx = (event.clientX - drag.startX) * factor;
    const dy = (event.clientY - drag.startY) * factor;
    if (Math.abs(dx) + Math.abs(dy) > 2) {
      drag.moved = true;
    }
    if (drag.kind === 'pan') {
      this.view.update((view) => ({ ...view, x: drag.originX + dx, y: drag.originY + dy }));
      return;
    }
    const node = this.nodes().find((item) => item.id === drag.id);
    if (node) {
      node.x = drag.originX + dx / this.view().k;
      node.y = drag.originY + dy / this.view().k;
      this.nodes.set([...this.nodes()]);
      if (this.frames >= MAX_FRAMES || !this.frame) {
        this.settle(false);
      }
    }
  }

  end(event: PointerEvent): void {
    const drag = this.drag;
    this.drag = null;
    if (!drag) {
      return;
    }
    (event.currentTarget as Element).releasePointerCapture?.(event.pointerId);
    if (drag.kind === 'node' && drag.id) {
      const node = this.nodes().find((item) => item.id === drag.id);
      if (node && !drag.moved) {
        node.pinned = false;
        this.select(node.id);
        const now = Date.now();
        if (this.lastClick.id === node.id && now - this.lastClick.at < 400) {
          this.expand(node);
        }
        this.lastClick = { id: node.id, at: now };
      }
      // A node that was dragged stays where it was put; "Rearrange" frees it.
    } else if (!drag.moved) {
      this.selectedId.set(null);
    }
  }

  zoom(event: WheelEvent): void {
    event.preventDefault();
    const svg = event.currentTarget as SVGSVGElement;
    const rect = svg.getBoundingClientRect();
    const factor = WIDTH / (rect.width || WIDTH);
    const px = (event.clientX - rect.left) * factor;
    const py = (event.clientY - rect.top) * factor;
    this.view.update((view) => {
      const k = Math.min(4, Math.max(0.3, view.k * (event.deltaY < 0 ? 1.12 : 1 / 1.12)));
      // The point under the pointer stays where it is while the drawing grows or shrinks.
      return { k, x: px - ((px - view.x) / view.k) * k, y: py - ((py - view.y) / view.k) * k };
    });
  }
}
