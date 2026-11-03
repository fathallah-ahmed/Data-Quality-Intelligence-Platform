import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { ApiService, apiErrorMessage } from '../../core/api.service';
import { QuestionAnswer, VersionDetails } from '../../core/models';
import { EmptyState } from '../../shared/components';
import { isActiveStatus } from '../../shared/format';
import { Icon } from '../../shared/icon';

/** One question and what came back, newest first. */
export interface Exchange {
  question: string;
  answer: QuestionAnswer | null;
  error: string | null;
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

/**
 * Questions in plain language about a version. The answer is written from the rows of a read-only query on the
 * graph and from the quality results; the query and the rows are shown so the answer can be checked.
 */
@Component({
  selector: 'app-ask-tab',
  imports: [EmptyState, Icon],
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

  readonly versionId = computed(() => this.details().version.id);
  readonly ready = computed(() => !isActiveStatus(this.details().version.status) && this.details().tables.length > 0);
  readonly cellText = cellText;
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

  private finish(exchange: Exchange, clear: boolean): void {
    this.history.update((list) => [exchange, ...list]);
    this.asking.set(false);
    if (clear) {
      this.question.set('');
    }
  }
}
