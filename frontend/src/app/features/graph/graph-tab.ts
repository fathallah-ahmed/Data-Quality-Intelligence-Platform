import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { ApiService, apiErrorMessage } from '../../core/api.service';
import { GraphBuild, GraphFinding, GraphSchema, GraphStatus, VersionDetails } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { EmptyState, Skeleton } from '../../shared/components';
import { isActiveStatus } from '../../shared/format';
import { Icon } from '../../shared/icon';
import { DQ_PIPES } from '../../shared/pipes';

/** Findings in the order they matter: conflicts first, then warnings, then notes. */
export function sortFindings(findings: GraphFinding[]): GraphFinding[] {
  const rank = (finding: GraphFinding) => (finding.severity === 'conflict' ? 0 : finding.severity === 'warning' ? 1 : 2);
  return [...findings].sort((a, b) => rank(a) - rank(b));
}

/** The Cypher that shows what a build wrote, for pasting in Neo4j Browser. */
export function browseQuery(scope: string): string {
  return `MATCH (n {_scope: '${scope}'}) OPTIONAL MATCH (n)-[r]->(m) RETURN n, r, m LIMIT 200`;
}

/**
 * How the rows of this version become a graph, and its build in Neo4j. The model is proposed from the
 * roles of the columns and from what the values do; nothing is written until the user builds it.
 */
@Component({
  selector: 'app-graph-tab',
  imports: [EmptyState, Skeleton, Icon, ...DQ_PIPES],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './graph-tab.html',
  styleUrl: './graph-tab.css'
})
export class GraphTab {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);

  readonly datasetId = input.required<number>();
  readonly details = input.required<VersionDetails>();

  readonly status = signal<GraphStatus | null>(null);
  readonly schema = signal<GraphSchema | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly promote = signal<string[]>([]);
  readonly naming = signal(false);
  readonly nameError = signal<string | null>(null);
  readonly building = signal(false);
  readonly built = signal<GraphBuild | null>(null);
  readonly confirmingDelete = signal(false);
  readonly deleting = signal(false);

  readonly versionId = computed(() => this.details().version.id);
  readonly ready = computed(() => !isActiveStatus(this.details().version.status) && this.details().tables.length > 0);
  readonly findings = computed(() => sortFindings(this.schema()?.findings ?? []));
  readonly builtFindings = computed(() => sortFindings(this.built()?.result.findings ?? []).filter((finding) => finding.kind !== 'BUILT'));
  readonly query = computed(() => browseQuery(this.built()?.result.scope ?? this.status()?.scope ?? ''));
  readonly renamed = computed(() => Object.keys(this.schema()?.renames ?? {}).length > 0);
  readonly nodeEntries = computed(() => Object.entries(this.built()?.result.nodes ?? {}));
  readonly relationshipEntries = computed(() => Object.entries(this.built()?.result.relationships ?? {}));

  private request = 0;

  constructor() {
    effect(() => {
      const key = `${this.datasetId()}:${this.versionId()}:${this.ready()}`;
      untracked(() => this.open(key));
    });
  }

  private open(_key: string): void {
    this.promote.set([]);
    this.built.set(null);
    this.schema.set(null);
    this.error.set(null);
    this.confirmingDelete.set(false);
    this.api.graphStatus(this.datasetId(), this.versionId()).subscribe({
      next: (status) => this.status.set(status),
      error: () => this.status.set(null)
    });
    if (!this.ready()) {
      return;
    }
    this.api.lastGraph(this.datasetId(), this.versionId()).subscribe({
      next: (last) => this.built.set(last.result ? (last as GraphBuild) : null),
      error: () => this.built.set(null)
    });
    this.loadSchema(false);
  }

  private loadSchema(refine: boolean): void {
    const request = ++this.request;
    this.loading.set(true);
    this.api.graphSchema(this.datasetId(), this.versionId(), this.promote(), refine).subscribe({
      next: (schema) => {
        if (request === this.request) {
          this.schema.set(schema);
          this.loading.set(false);
          this.naming.set(false);
          this.error.set(null);
        }
      },
      error: (error) => {
        if (request === this.request) {
          this.loading.set(false);
          this.naming.set(false);
          if (refine) {
            this.nameError.set(apiErrorMessage(error, 'The names could not be proposed.'));
          } else {
            this.error.set(apiErrorMessage(error, 'The model of the graph could not be built.'));
          }
        }
      }
    });
  }

  isPromoted(table: string, column: string): boolean {
    return this.promote().includes(`${table}.${column}`);
  }

  togglePromote(table: string, column: string): void {
    const key = `${table}.${column}`;
    this.promote.update((current) => (current.includes(key) ? current.filter((item) => item !== key) : [...current, key]));
    this.nameError.set(null);
    this.loadSchema(false);
  }

  /** Ask the local model to name the nodes and relationships in the vocabulary of the dataset. */
  nameWithAi(): void {
    this.naming.set(true);
    this.nameError.set(null);
    this.loadSchema(true);
  }

  resetNames(): void {
    this.nameError.set(null);
    this.loadSchema(false);
  }

  build(): void {
    const schema = this.schema();
    if (!schema || this.building()) {
      return;
    }
    this.building.set(true);
    this.api.buildGraph(this.datasetId(), this.versionId(), this.promote(), schema.renames).subscribe({
      next: (build) => {
        this.building.set(false);
        this.built.set(build);
        this.toasts.success(`Graph built in Neo4j: ${Object.values(build.result.nodes).reduce((sum, count) => sum + count, 0)} node(s).`);
        this.api.graphStatus(this.datasetId(), this.versionId()).subscribe((status) => this.status.set(status));
      },
      error: (error) => {
        this.building.set(false);
        this.toasts.error(apiErrorMessage(error, 'The graph could not be built.'));
        this.api.graphStatus(this.datasetId(), this.versionId()).subscribe((status) => this.status.set(status));
      }
    });
  }

  /** Two steps, so a graph is never removed by a stray click. */
  deleteGraph(): void {
    if (!this.confirmingDelete()) {
      this.confirmingDelete.set(true);
      return;
    }
    this.deleting.set(true);
    this.api.deleteGraph(this.datasetId(), this.versionId()).subscribe({
      next: () => {
        this.deleting.set(false);
        this.confirmingDelete.set(false);
        this.built.set(null);
        this.toasts.success('The graph of this version was removed from Neo4j.');
        this.api.graphStatus(this.datasetId(), this.versionId()).subscribe((status) => this.status.set(status));
      },
      error: (error) => {
        this.deleting.set(false);
        this.confirmingDelete.set(false);
        this.toasts.error(apiErrorMessage(error, 'The graph could not be removed.'));
      }
    });
  }

  roleText(role: string): string {
    return role.toLowerCase().replace('_', ' ');
  }
}
