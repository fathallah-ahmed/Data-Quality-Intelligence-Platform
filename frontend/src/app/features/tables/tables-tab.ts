import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, input, output, signal } from '@angular/core';
import { TableAnalysisView, VersionDetails } from '../../core/models';
import { EmptyState, LlmBadge, StatusBadge } from '../../shared/components';
import { scoreTone } from '../../shared/format';
import { DQ_PIPES } from '../../shared/pipes';
import { TableDetail } from './table-detail';

@Component({
  selector: 'app-tables-tab',
  imports: [TableDetail, StatusBadge, LlmBadge, EmptyState, ...DQ_PIPES],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './tables-tab.html',
  styleUrl: './tables-tab.css'
})
export class TablesTab {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  readonly details = input.required<VersionDetails>();
  readonly selected = input<TableAnalysisView | null>(null);
  readonly detail = input<TableAnalysisView | null>(null);
  readonly detailLoading = input(false);
  readonly selectTable = output<string>();
  readonly filter = signal('');

  readonly failedNames = computed(() => new Set((this.details().failedTables ?? []).map((item) => item.tableName)));
  readonly tables = computed(() => {
    const query = this.filter().trim().toLowerCase();
    return this.details().tables.filter((table) => !query || table.tableName.toLowerCase().includes(query));
  });

  tone(score: number | null | undefined): string {
    return scoreTone(score);
  }

  failureFor(name: string): string | null {
    return (this.details().failedTables ?? []).find((item) => item.tableName === name)?.error ?? null;
  }

  /** Arrow keys move through the table list (listbox pattern). */
  onListKeydown(event: KeyboardEvent): void {
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      return;
    }
    const options = Array.from(this.host.nativeElement.querySelectorAll<HTMLButtonElement>('.table-list__item'));
    const current = options.indexOf(document.activeElement as HTMLButtonElement);
    const last = options.length - 1;
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? last
      : event.key === 'ArrowDown' ? Math.min(last, current + 1) : Math.max(0, current - 1);
    event.preventDefault();
    options[next]?.focus();
    options[next]?.click();
  }
}
