import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService, apiErrorMessage } from '../../core/api.service';
import {
  CorrectionLog,
  CorrectionOp,
  CorrectionPreview,
  CorrectionSuggestion,
  CorrectionSuggestions,
  VersionDetails
} from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { WorkspaceStore } from '../../core/workspace.store';
import { ConfirmDialog, EmptyState, Skeleton } from '../../shared/components';
import { isActiveStatus } from '../../shared/format';
import { Icon } from '../../shared/icon';
import { DQ_PIPES } from '../../shared/pipes';

/** A correction waiting in the list; where it came from decides how it is labelled. */
export interface PlanItem {
  operation: CorrectionOp;
  description: string;
  origin: 'found' | 'written';
}

/**
 * Corrections of a table. They come from what was found in the data or from a request written in plain
 * language; either way they are checked, previewed with counts and examples, and only then applied to a
 * copy: the result is a new version, and the version being corrected is never changed.
 */
@Component({
  selector: 'app-corrections-tab',
  imports: [ConfirmDialog, EmptyState, Skeleton, Icon, RouterLink, ...DQ_PIPES],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './corrections-tab.html',
  styleUrl: './corrections-tab.css'
})
export class CorrectionsTab {
  private readonly api = inject(ApiService);
  private readonly store = inject(WorkspaceStore);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);

  readonly datasetId = input.required<number>();
  readonly details = input.required<VersionDetails>();

  readonly chosenTable = signal('');
  readonly suggestions = signal<CorrectionSuggestions | null>(null);
  readonly suggestionsLoading = signal(false);
  readonly suggestionsError = signal<string | null>(null);

  readonly plan = signal<PlanItem[]>([]);
  readonly instruction = signal('');
  readonly interpreting = signal(false);
  readonly assumptions = signal('');
  readonly rejected = signal<string[]>([]);
  readonly requestError = signal<string | null>(null);

  readonly preview = signal<CorrectionPreview | null>(null);
  readonly previewing = signal(false);
  readonly previewError = signal<string | null>(null);
  readonly openOperation = signal<number | null>(null);

  readonly confirmApply = signal(false);
  readonly applying = signal(false);
  readonly log = signal<CorrectionLog | null>(null);

  readonly tableNames = computed(() => this.details().tables.filter((table) => table.changeStatus !== 'REMOVED').map((table) => table.tableName));
  readonly table = computed(() => (this.tableNames().includes(this.chosenTable()) ? this.chosenTable() : this.tableNames()[0] ?? ''));
  readonly versionId = computed(() => this.details().version.id);
  readonly running = computed(() => isActiveStatus(this.details().version.status));
  readonly nextVersion = computed(() => Math.max(...this.store.versions().map((version) => version.versionNumber), this.details().version.versionNumber) + 1);
  readonly addedKeys = computed(() => new Set(this.plan().map((item) => item.operation.type + ':' + (item.operation.column ?? ''))));
  readonly canApply = computed(() => this.plan().length > 0 && !this.running() && !this.applying() && !this.previewing() && !this.previewError());

  /** The version this one was corrected from, for the link to compare the two. */
  readonly sourceVersion = computed(() => {
    const number = this.details().version.derivedFromVersionNumber;
    return number ? this.store.versions().find((version) => version.versionNumber === number) ?? null : null;
  });

  private suggestionRequest = 0;
  private previewRequest = 0;

  constructor() {
    // A new table or version starts from an empty list of corrections.
    effect(() => {
      const key = `${this.datasetId()}:${this.versionId()}:${this.table()}`;
      untracked(() => this.reset(key));
    });
    // Whenever the list changes, show what it would do.
    effect(() => {
      const items = this.plan();
      untracked(() => this.refreshPreview(items));
    });
    effect(() => {
      const derived = this.details().version.derivedFromVersionNumber;
      untracked(() => this.loadLog(derived));
    });
  }

  private reset(_key: string): void {
    this.plan.set([]);
    this.preview.set(null);
    this.assumptions.set('');
    this.rejected.set([]);
    this.requestError.set(null);
    this.openOperation.set(null);
    this.loadSuggestions();
  }

  private loadSuggestions(): void {
    const table = this.table();
    if (!table) {
      this.suggestions.set(null);
      return;
    }
    const request = ++this.suggestionRequest;
    this.suggestionsLoading.set(true);
    this.suggestionsError.set(null);
    this.api.correctionSuggestions(this.datasetId(), this.versionId(), table).subscribe({
      next: (found) => {
        if (request === this.suggestionRequest) {
          this.suggestions.set(found);
          this.suggestionsLoading.set(false);
        }
      },
      error: (error) => {
        if (request === this.suggestionRequest) {
          this.suggestionsError.set(apiErrorMessage(error, 'Could not read the table.'));
          this.suggestionsLoading.set(false);
        }
      }
    });
  }

  private loadLog(derived: number | null | undefined): void {
    if (!derived) {
      this.log.set(null);
      return;
    }
    this.api.correctionLog(this.datasetId(), this.versionId()).subscribe({
      next: (log) => this.log.set(log),
      error: () => this.log.set(null)
    });
  }

  private refreshPreview(items: PlanItem[]): void {
    const request = ++this.previewRequest;
    this.previewError.set(null);
    if (items.length === 0) {
      this.preview.set(null);
      this.previewing.set(false);
      return;
    }
    this.previewing.set(true);
    this.api.previewCorrections(this.datasetId(), this.versionId(), this.table(), items.map((item) => item.operation)).subscribe({
      next: (preview) => {
        if (request === this.previewRequest) {
          this.preview.set(preview);
          this.previewing.set(false);
        }
      },
      error: (error) => {
        if (request === this.previewRequest) {
          this.previewError.set(apiErrorMessage(error, 'Could not preview the corrections.'));
          this.previewing.set(false);
        }
      }
    });
  }

  selectTable(name: string): void {
    this.chosenTable.set(name);
  }

  isAdded(suggestion: CorrectionSuggestion): boolean {
    return this.addedKeys().has(suggestion.operation.type + ':' + (suggestion.operation.column ?? ''));
  }

  addSuggestion(suggestion: CorrectionSuggestion): void {
    if (!this.isAdded(suggestion)) {
      this.plan.update((items) => [...items, { operation: suggestion.operation, description: suggestion.description, origin: 'found' }]);
    }
  }

  addAllSuggestions(): void {
    (this.suggestions()?.suggestions ?? []).forEach((suggestion) => this.addSuggestion(suggestion));
  }

  remove(index: number): void {
    this.plan.update((items) => items.filter((_, position) => position !== index));
    this.openOperation.set(null);
  }

  clear(): void {
    this.plan.set([]);
  }

  /** Read the written request as corrections and add them to the list; the preview shows their effect. */
  interpret(): void {
    const text = this.instruction().trim();
    if (!text || this.interpreting()) {
      return;
    }
    this.interpreting.set(true);
    this.requestError.set(null);
    this.api.interpretCorrection(this.datasetId(), this.versionId(), this.table(), text).subscribe({
      next: (result) => {
        this.interpreting.set(false);
        this.assumptions.set(result.assumptions);
        this.rejected.set(result.rejected);
        if (result.operations.length > 0) {
          this.plan.update((items) => [
            ...items,
            ...result.operations.map((view): PlanItem => ({ operation: view.operation, description: view.description, origin: 'written' }))
          ]);
          this.instruction.set('');
        }
      },
      error: (error) => {
        this.interpreting.set(false);
        this.requestError.set(apiErrorMessage(error, 'The request could not be read.'));
      }
    });
  }

  toggleOperation(index: number): void {
    this.openOperation.update((current) => (current === index ? null : index));
  }

  apply(): void {
    if (!this.canApply()) {
      return;
    }
    this.applying.set(true);
    this.api.applyCorrections(this.datasetId(), this.versionId(), this.table(), this.plan().map((item) => item.operation)).subscribe({
      next: (response) => {
        this.applying.set(false);
        this.confirmApply.set(false);
        if (response.airflowTriggered) {
          this.toasts.success(`Version ${response.versionNumber} created with your corrections. Its analysis has started.`);
        } else {
          this.toasts.error(`Version ${response.versionNumber} was created, but its analysis could not start. ${response.airflowMessage}`);
        }
        this.store.loadDatasets();
        this.store.refresh();
        this.router.navigate(['/datasets', response.datasetId, 'versions', response.versionId, 'overview']);
      },
      error: (error) => {
        this.applying.set(false);
        this.confirmApply.set(false);
        this.toasts.error(apiErrorMessage(error, 'The corrections could not be applied.'));
      }
    });
  }
}
