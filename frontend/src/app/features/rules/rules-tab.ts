import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { forkJoin, switchMap } from 'rxjs';
import { ApiService, apiErrorMessage } from '../../core/api.service';
import { RuleFormat, RuleRequest, RuleResultView, RuleResults, RuleSeverity, RuleType, RuleView, VersionDetails } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { ConfirmDialog, EmptyState, Skeleton } from '../../shared/components';
import { Icon } from '../../shared/icon';
import { RULE_FORMATS, RULE_TYPES, describeRule, ruleTypeInfo, suggestRules } from './rule-types';

/** Data-quality rules of the dataset, and how this version fares against them. */
@Component({
  selector: 'app-rules-tab',
  imports: [ConfirmDialog, EmptyState, Skeleton, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './rules-tab.html',
  styleUrl: './rules-tab.css'
})
export class RulesTab {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);

  readonly datasetId = input.required<number>();
  readonly details = input.required<VersionDetails>();
  /** Emitted after the rules or their results changed, so the page can refresh its summaries. */
  readonly rulesChanged = output<void>();

  readonly ruleTypes = RULE_TYPES;
  readonly ruleFormats = RULE_FORMATS;
  /** Every rule the dataset has, including suggestions that wait for a decision. */
  readonly allRules = signal<RuleView[]>([]);
  /** Rules that are approved and checked on every version. */
  readonly rules = computed(() => this.allRules().filter((rule) => rule.state !== 'SUGGESTED'));
  /** Rules the analysis proposed from the user's goal; nothing is checked until they are approved. */
  readonly goalSuggestions = computed(() => this.allRules().filter((rule) => rule.state === 'SUGGESTED'));
  readonly results = signal<RuleResults | null>(null);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly ruleToDelete = signal<RuleView | null>(null);

  // New-rule form
  readonly formTable = signal('');
  readonly formType = signal<RuleType>('NOT_NULL');
  readonly formColumn = signal('');
  readonly formThreshold = signal<number | null>(null);
  readonly formSeverity = signal<RuleSeverity>('WARNING');
  readonly formFormat = signal<RuleFormat>('EMAIL');
  readonly formSecond = signal('');

  readonly tableNames = computed(() => this.details().tables.filter((table) => table.changeStatus !== 'REMOVED').map((table) => table.tableName));
  readonly table = computed(() => this.formTable() || this.tableNames()[0] || '');
  readonly typeInfo = computed(() => ruleTypeInfo(this.formType()));
  readonly columns = computed(() => this.details().tables.find((table) => table.tableName === this.table())?.columns.map((column) => column.columnName) ?? []);
  readonly column = computed(() => (this.columns().includes(this.formColumn()) ? this.formColumn() : this.columns()[0] ?? ''));
  /** The columns a date can be compared with: every other column of the table. */
  readonly secondColumns = computed(() => this.columns().filter((name) => name !== this.column()));
  readonly secondColumn = computed(() => (this.secondColumns().includes(this.formSecond()) ? this.formSecond() : this.secondColumns()[0] ?? ''));
  readonly preview = computed(() => describeRule({
    type: this.formType(),
    tableName: this.table() || 'table',
    columnName: this.typeInfo().needsColumn ? this.column() || 'column' : null,
    threshold: this.threshold(),
    format: this.typeInfo().needsFormat ? this.formFormat() : null,
    secondColumn: this.typeInfo().needsSecondColumn ? this.secondColumn() : null
  }));
  readonly threshold = computed(() => (this.typeInfo().needsThreshold ? this.formThreshold() ?? this.typeInfo().defaultThreshold : null));
  readonly canAdd = computed(() => !this.busy() && !!this.table()
    && (!this.typeInfo().needsColumn || !!this.column())
    && (!this.typeInfo().needsSecondColumn || !!this.secondColumn())
    && (!this.typeInfo().needsThreshold || this.threshold() !== null));

  readonly suggestions = computed(() => suggestRules(this.details(), this.allRules()));
  readonly evaluated = computed(() => (this.results()?.results.length ?? 0) > 0);
  readonly enabledCount = computed(() => this.rules().filter((rule) => rule.enabled).length);
  readonly notChecked = computed(() => this.enabledCount() > 0 && !this.evaluated());

  /** Failing rules first (critical before warning), then passing, then the ones that could not be checked. */
  readonly sortedResults = computed<RuleResultView[]>(() => {
    const rank = (result: RuleResultView) => (result.status === 'FAIL' ? (result.severity === 'CRITICAL' ? 0 : 1) : result.status === 'PASS' ? 2 : 3);
    return [...(this.results()?.results ?? [])].sort((a, b) => rank(a) - rank(b));
  });
  readonly passShare = computed(() => {
    const summary = this.results()?.summary;
    const checked = summary ? summary.passed + summary.failed : 0;
    return checked > 0 && summary ? (summary.passed / checked) * 100 : 0;
  });

  private readonly requestKey = computed(() => `${this.datasetId()}:${this.details().version.id}:${this.details().version.status}`);

  constructor() {
    effect(() => {
      this.requestKey();
      untracked(() => this.load());
    });
  }

  load(): void {
    this.loading.set(true);
    forkJoin({
      rules: this.api.rules(this.datasetId()),
      results: this.api.ruleResults(this.datasetId(), this.details().version.id)
    }).subscribe({
      next: ({ rules, results }) => {
        this.allRules.set(rules);
        this.results.set(results);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(apiErrorMessage(error, 'Could not load the quality rules.'));
        this.loading.set(false);
      }
    });
  }

  setType(value: string): void {
    this.formType.set(value as RuleType);
    this.formThreshold.set(null);
  }

  submit(): void {
    if (!this.canAdd()) {
      return;
    }
    const info = this.typeInfo();
    this.save({
      tableName: this.table(),
      columnName: info.needsColumn ? this.column() : null,
      type: this.formType(),
      threshold: info.needsThreshold ? this.threshold() : null,
      severity: this.formSeverity(),
      format: info.needsFormat ? this.formFormat() : null,
      secondColumn: info.needsSecondColumn ? this.secondColumn() : null
    }, 'Rule added.');
  }

  /** Switch on a rule proposed from the goal, then check it against this version. */
  approve(rule: RuleView): void {
    this.busy.set(true);
    this.api.approveRule(this.datasetId(), rule.id)
      .pipe(switchMap(() => this.api.evaluateRules(this.datasetId(), this.details().version.id)))
      .subscribe({
        next: () => this.done('Rule approved and checked.'),
        error: (error) => this.failed(error, 'Could not approve the rule.')
      });
  }

  approveAll(): void {
    const pending = this.goalSuggestions();
    if (pending.length === 0 || this.busy()) {
      return;
    }
    this.busy.set(true);
    forkJoin(pending.map((rule) => this.api.approveRule(this.datasetId(), rule.id)))
      .pipe(switchMap(() => this.api.evaluateRules(this.datasetId(), this.details().version.id)))
      .subscribe({
        next: () => this.done(`${pending.length} rules approved and checked.`),
        error: (error) => this.failed(error, 'Could not approve the rules.')
      });
  }

  /** Turn a suggestion down; it is hidden and not proposed again. */
  dismiss(rule: RuleView): void {
    this.busy.set(true);
    this.api.dismissRule(this.datasetId(), rule.id).subscribe({
      next: () => this.done('Suggestion dismissed.'),
      error: (error) => this.failed(error, 'Could not dismiss the suggestion.')
    });
  }

  addSuggestion(rule: RuleRequest): void {
    this.save(rule, 'Rule added.');
  }

  addAllSuggestions(): void {
    const suggestions = this.suggestions();
    if (suggestions.length === 0 || this.busy()) {
      return;
    }
    this.busy.set(true);
    forkJoin(suggestions.map((rule) => this.api.createRule(this.datasetId(), rule)))
      .pipe(switchMap(() => this.api.evaluateRules(this.datasetId(), this.details().version.id)))
      .subscribe({
        next: () => this.done(`${suggestions.length} rules added.`),
        error: (error) => this.failed(error, 'Could not add the rules.')
      });
  }

  toggle(rule: RuleView): void {
    this.busy.set(true);
    this.api.updateRule(this.datasetId(), rule.id, this.request(rule, !rule.enabled))
      .pipe(switchMap(() => this.api.evaluateRules(this.datasetId(), this.details().version.id)))
      .subscribe({
        next: () => this.done(rule.enabled ? 'Rule paused.' : 'Rule enabled.'),
        error: (error) => this.failed(error, 'Could not update the rule.')
      });
  }

  confirmDelete(): void {
    const rule = this.ruleToDelete();
    if (!rule) {
      return;
    }
    this.busy.set(true);
    this.api.deleteRule(this.datasetId(), rule.id)
      .pipe(switchMap(() => this.api.evaluateRules(this.datasetId(), this.details().version.id)))
      .subscribe({
        next: () => {
          this.ruleToDelete.set(null);
          this.done('Rule deleted.');
        },
        error: (error) => {
          this.ruleToDelete.set(null);
          this.failed(error, 'Could not delete the rule.');
        }
      });
  }

  recheck(): void {
    this.busy.set(true);
    this.api.evaluateRules(this.datasetId(), this.details().version.id).subscribe({
      next: () => this.done('Rules checked against this version.'),
      error: (error) => this.failed(error, 'Could not check the rules.')
    });
  }

  describe(rule: RuleRequest): string {
    return describeRule(rule);
  }

  private save(rule: RuleRequest, message: string): void {
    this.busy.set(true);
    this.api.createRule(this.datasetId(), rule)
      .pipe(switchMap(() => this.api.evaluateRules(this.datasetId(), this.details().version.id)))
      .subscribe({
        next: () => this.done(message),
        error: (error) => this.failed(error, 'Could not save the rule.')
      });
  }

  private request(rule: RuleView, enabled: boolean): RuleRequest {
    return {
      tableName: rule.tableName,
      columnName: rule.columnName,
      type: rule.type,
      threshold: rule.threshold,
      severity: rule.severity,
      enabled,
      format: rule.format,
      secondColumn: rule.secondColumn
    };
  }

  private done(message: string): void {
    this.busy.set(false);
    this.toasts.success(message);
    this.load();
    this.rulesChanged.emit();
  }

  private failed(error: unknown, fallback: string): void {
    this.busy.set(false);
    this.toasts.error(apiErrorMessage(error, fallback));
  }
}
