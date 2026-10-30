import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ToastService } from '../core/toast.service';
import { Tone, llmStatusInfo, statusLabel, statusTone } from './format';

/** Run / change status as a coloured badge with a human label. */
@Component({
  selector: 'app-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="badge" [attr.data-tone]="tone()" [attr.title]="title() || null">{{ label() }}</span>`
})
export class StatusBadge {
  readonly status = input<string | null | undefined>(null);
  readonly title = input<string>('');
  readonly label = computed(() => statusLabel(this.status()));
  readonly tone = computed(() => statusTone(this.status()));
}

/** "AI insights" / "Deterministic only – Ollama unavailable" / "AI answer rejected". */
@Component({
  selector: 'app-llm-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (info(); as info) {
      <span class="badge badge--soft" [attr.data-tone]="info.tone" [attr.title]="info.hint">
        <span class="badge__dot" aria-hidden="true"></span>{{ compact() ? shortLabel() : info.label }}
      </span>
    }
  `
})
export class LlmBadge {
  readonly status = input<string | null | undefined>(null);
  readonly compact = input(false);
  readonly info = computed(() => llmStatusInfo(this.status()));
  readonly shortLabel = computed(() => {
    const status = (this.status() ?? '').toLowerCase();
    return status === 'unavailable' ? 'No AI' : status === 'invalid' ? 'AI rejected' : this.info()?.label ?? '';
  });
}

@Component({
  selector: 'app-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="empty-state" [class.empty-state--compact]="compact()">
      <div class="empty-state__icon" aria-hidden="true">{{ icon() }}</div>
      <h3>{{ title() }}</h3>
      @if (message()) {
        <p>{{ message() }}</p>
      }
      <div class="empty-state__actions"><ng-content /></div>
    </div>
  `
})
export class EmptyState {
  readonly title = input.required<string>();
  readonly message = input<string>('');
  readonly icon = input<string>('◇');
  readonly compact = input(false, { transform: booleanAttribute });
}

@Component({
  selector: 'app-skeleton',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @for (line of lines(); track $index) {
      <div class="skeleton" [style.height.px]="height()" [style.width]="$last && lines().length > 1 ? '60%' : '100%'"></div>
    }
  `,
  host: { 'aria-hidden': 'true', class: 'skeleton-group' }
})
export class Skeleton {
  readonly count = input(1);
  readonly height = input(16);
  readonly lines = computed(() => Array.from({ length: this.count() }));
}

/** Small "?" button that shows an explanation on hover or keyboard focus. */
@Component({
  selector: 'app-info-tip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="info-tip">
      <button type="button" class="info-tip__button" [attr.aria-label]="label()" [attr.aria-describedby]="id">?</button>
      <span class="info-tip__bubble" role="tooltip" [id]="id">{{ text() }}</span>
    </span>
  `
})
export class InfoTip {
  private static nextId = 0;
  readonly id = `tip-${InfoTip.nextId++}`;
  readonly text = input.required<string>();
  readonly label = input('More information');
}

/** Native <dialog>: focus trap, Escape to close and inert background for free. */
@Component({
  selector: 'app-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog #dialog class="modal" [class.modal--wide]="wide()" [attr.aria-labelledby]="titleId" (cancel)="onCancel($event)" (click)="onBackdrop($event)">
      <div class="modal__panel">
        <header class="modal__header">
          <h2 [id]="titleId">{{ title() }}</h2>
          <button type="button" class="btn btn--ghost btn--icon" aria-label="Close" (click)="closed.emit()">✕</button>
        </header>
        <div class="modal__body"><ng-content /></div>
        <footer class="modal__footer"><ng-content select="[modal-actions]" /></footer>
      </div>
    </dialog>
  `
})
export class Modal {
  private static nextId = 0;
  readonly titleId = `modal-title-${Modal.nextId++}`;
  readonly open = input(false);
  readonly title = input.required<string>();
  readonly wide = input(false);
  /** Block closing (Escape / backdrop) while work is in progress. */
  readonly busy = input(false);
  readonly closed = output<void>();
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    effect(() => {
      const element = this.dialog().nativeElement;
      if (this.open() && !element.open) {
        element.showModal?.();
      } else if (!this.open() && element.open) {
        element.close();
      }
    });
  }

  onCancel(event: Event): void {
    event.preventDefault();
    if (!this.busy()) {
      this.closed.emit();
    }
  }

  onBackdrop(event: MouseEvent): void {
    if (event.target === this.dialog().nativeElement && !this.busy()) {
      this.closed.emit();
    }
  }
}

/** Confirmation, optionally requiring the user to type a word (e.g. the dataset name). */
@Component({
  selector: 'app-confirm-dialog',
  imports: [Modal, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-modal [open]="open()" [title]="title()" [busy]="busy()" (closed)="cancelled.emit()">
      <p class="confirm__message">{{ message() }}</p>
      @if (requireText()) {
        <label class="field">
          <span class="field__label">Type <strong>{{ requireText() }}</strong> to confirm</span>
          <input class="input" type="text" autocomplete="off" [ngModel]="typed()" (ngModelChange)="typed.set($event)" />
        </label>
      }
      <div modal-actions>
        <button type="button" class="btn btn--secondary" [disabled]="busy()" (click)="cancelled.emit()">Cancel</button>
        <button type="button" class="btn" [class.btn--danger]="danger()" [class.btn--primary]="!danger()" [disabled]="!canConfirm() || busy()" (click)="confirmed.emit()">
          {{ busy() ? 'Working…' : confirmText() }}
        </button>
      </div>
    </app-modal>
  `
})
export class ConfirmDialog {
  readonly open = input(false);
  readonly title = input.required<string>();
  readonly message = input<string>('');
  readonly confirmText = input('Confirm');
  readonly requireText = input<string>('');
  readonly danger = input(false);
  readonly busy = input(false);
  readonly confirmed = output<void>();
  readonly cancelled = output<void>();
  readonly typed = signal('');
  readonly canConfirm = computed(() => !this.requireText() || this.typed().trim() === this.requireText());

  constructor() {
    effect(() => {
      if (this.open()) {
        this.typed.set('');
      }
    });
  }
}

export interface TabItem {
  id: string;
  label: string;
  link: unknown[];
  count?: number | null;
}

/** Router-driven tabs with arrow-key navigation (WAI-ARIA tabs pattern). */
@Component({
  selector: 'app-tabs',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="tabs" role="tablist" [attr.aria-label]="label()" (keydown)="onKeydown($event)">
      @for (tab of tabs(); track tab.id) {
        <a
          class="tabs__tab"
          role="tab"
          [routerLink]="tab.link"
          queryParamsHandling="preserve"
          [attr.aria-selected]="tab.id === active()"
          [attr.tabindex]="tab.id === active() ? 0 : -1"
          [class.tabs__tab--active]="tab.id === active()"
        >
          {{ tab.label }}
          @if (tab.count !== null && tab.count !== undefined) {
            <span class="tabs__count">{{ tab.count }}</span>
          }
        </a>
      }
    </nav>
  `
})
export class Tabs {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  readonly tabs = input.required<TabItem[]>();
  readonly active = input<string>('');
  readonly label = input('Sections');

  onKeydown(event: KeyboardEvent): void {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
      return;
    }
    const links = Array.from(this.host.nativeElement.querySelectorAll<HTMLAnchorElement>('[role="tab"]'));
    const current = links.indexOf(document.activeElement as HTMLAnchorElement);
    const last = links.length - 1;
    const next = event.key === 'Home' ? 0
      : event.key === 'End' ? last
      : event.key === 'ArrowRight' ? (current >= last ? 0 : current + 1)
      : (current <= 0 ? last : current - 1);
    event.preventDefault();
    links[next]?.focus();
    links[next]?.click();
  }
}

@Component({
  selector: 'app-toast-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toasts" role="status" aria-live="polite" aria-relevant="additions">
      @for (toast of toasts.toasts(); track toast.id) {
        <div class="toast" [attr.data-tone]="toast.tone">
          <span class="toast__icon" aria-hidden="true">{{ icon(toast.tone) }}</span>
          <p>{{ toast.message }}</p>
          <button type="button" class="btn btn--ghost btn--icon" aria-label="Dismiss" (click)="toasts.dismiss(toast.id)">✕</button>
        </div>
      }
    </div>
  `
})
export class ToastHost {
  readonly toasts = inject(ToastService);

  icon(tone: Tone | string): string {
    return tone === 'success' ? '✓' : tone === 'error' ? '!' : tone === 'warning' ? '⚠' : 'i';
  }
}
