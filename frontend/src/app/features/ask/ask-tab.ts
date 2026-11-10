import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { ApiService, apiErrorMessage } from '../../core/api.service';
import { QuestionAnswer, Subgraph, VersionDetails } from '../../core/models';
import { EmptyState } from '../../shared/components';
import { isActiveStatus } from '../../shared/format';
import { Icon } from '../../shared/icon';
import { GraphView } from './graph-view';

/** One question and what came back, newest first. */
export interface Exchange {
  /** Stays the same while the exchange is updated (the drawing of its graph arrives later). */
  id?: number;
  question: string;
  answer: QuestionAnswer | null;
  error: string | null;
  /** The drawing of what the answer rests on, asked for on demand (it costs one more model call). */
  subgraph?: Subgraph | null;
  subgraphState?: 'loading' | 'error' | 'empty' | 'ready';
  subgraphError?: string;
}

export const EXAMPLE_QUESTIONS = [
  'Which rules failed and why?',
  'Which column has the most missing values?',
  'How is the quality score computed?',
  'How do I correct the dates of a table?',
  'How many nodes of each kind are in the graph?',
  'Which entities have conflicting values?'
];

/** Where an answer comes from, in words. */
export function sourceLabel(mode: QuestionAnswer['mode']): string {
  switch (mode) {
    case 'graph':
      return 'From the graph';
    case 'help':
      return 'From the guide of the application';
    default:
      return 'From the quality results';
  }
}

/** Shows a cell of a result row as text (objects and lists as JSON). */
export function cellText(value: unknown): string {
  if (value === null || value === undefined) {
    return '';
  }
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

/** The cells of a result row in the order of the columns. The service sends a row as an object (column → value). */
export function rowCells(row: unknown, columns: string[]): unknown[] {
  if (Array.isArray(row)) {
    return row;
  }
  const record = (row ?? {}) as Record<string, unknown>;
  return columns.map((column) => record[column]);
}

/**
 * Questions in plain language about a version. The answer is written from the rows of a read-only query on the
 * graph and from the quality results; the query and the rows are shown so the answer can be checked.
 */
@Component({
  selector: 'app-ask-tab',
  imports: [EmptyState, Icon, GraphView],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './ask-tab.html',
  styleUrl: './ask-tab.css'
})
export class AskTab {
  private readonly api = inject(ApiService);

  readonly datasetId = input.required<number>();
  readonly details = input.required<VersionDetails>();

  readonly examples = EXAMPLE_QUESTIONS;
  readonly question = signal('');
  readonly asking = signal(false);
  readonly history = signal<Exchange[]>([]);
  private nextId = 1;

  readonly versionId = computed(() => this.details().version.id);
  readonly ready = computed(() => !isActiveStatus(this.details().version.status) && this.details().tables.length > 0);
  readonly cellText = cellText;
  readonly rowCells = rowCells;
  readonly sourceLabel = sourceLabel;

  constructor() {
    effect(() => {
      this.datasetId();
      this.versionId();
      untracked(() => {
        this.history.set([]);
      });
    });
  }

  use(example: string): void {
    this.question.set(example);
  }

  ask(): void {
    const text = this.question().trim();
    if (!text || this.asking()) {
      return;
    }
    this.asking.set(true);
    this.api.askQuestion(this.datasetId(), this.versionId(), text).subscribe({
      next: (answer) => this.finish({ question: text, answer, error: null }, true),
      error: (error) => this.finish({ question: text, answer: null, error: apiErrorMessage(error, 'The question could not be answered.') }, false)
    });
  }

  /** Draws the nodes and relationships behind an answer. */
  showGraph(exchange: Exchange): void {
    const answer = exchange.answer;
    if (!answer?.cypher || exchange.subgraphState === 'loading') {
      return;
    }
    this.patch(exchange, { subgraphState: 'loading', subgraphError: undefined });
    this.api.answerSubgraph(this.datasetId(), this.versionId(), exchange.question, answer.cypher).subscribe({
      next: (subgraph) =>
        this.patch(exchange, subgraph.nodes.length
          ? { subgraph, subgraphState: 'ready' }
          : { subgraph: null, subgraphState: 'empty', subgraphError: subgraph.warnings?.join(' ') }),
      error: (error) => this.patch(exchange, { subgraphState: 'error', subgraphError: apiErrorMessage(error, 'The graph could not be drawn.') })
    });
  }

  private patch(exchange: Exchange, change: Partial<Exchange>): void {
    this.history.update((list) => list.map((item) => (item.id === exchange.id ? { ...item, ...change } : item)));
  }

  private finish(exchange: Exchange, clear: boolean): void {
    this.history.update((list) => [{ ...exchange, id: this.nextId++ }, ...list]);
    this.asking.set(false);
    if (clear) {
      this.question.set('');
    }
  }
}
