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
  untracked,
  viewChild
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ToastService, ToastTone } from '../core/toast.service';
import { formatScore, llmStatusInfo, qualityGrade, scoreTone, statusLabel, statusTone } from './format';
import { Icon, IconName } from './icon';
import { animateNumber } from './motion';

/** Run / change status as a coloured badge with a human label. */
@Component({
  selector: 'app-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="badge" [attr.data-tone]="tone()" [attr.title]="title() || null"><span class="badge__dot" aria-hidden="true"></span>{{ label() }}</span>`
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
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (info(); as info) {
      <span class="badge" [attr.data-tone]="info.tone" [attr.title]="info.hint">
        <app-icon name="sparkles" [size]="13" />{{ compact() ? shortLabel() : info.label }}
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
    return status === 'unavailable' ? 'No AI' : status === 'invalid' ? 'AI rejected' : status === 'ok' ? 'AI' : this.info()?.label ?? '';
  });
}

const COUNT_UP_MS = 1100;

/**
 * Circular score gauge (0–100) coloured by the quality scale; it draws itself and counts up when it appears.
 * The number is written straight to the DOM on every frame, so counting never triggers a change-detection pass.
 */
@Component({
  selector: 'app-score-ring',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg [attr.width]="size()" [attr.height]="size()" viewBox="0 0 100 100" role="img" [attr.aria-label]="ariaLabel()">
      <circle cx="50" cy="50" [attr.r]="radius" fill="none" class="ring__track" [attr.stroke-width]="stroke()" />
      @if (value() !== null && value() !== undefined) {
        <circle cx="50" cy="50" [attr.r]="radius" fill="none" class="ring__value" [attr.stroke-width]="stroke()"
          stroke-linecap="round" [attr.stroke-dasharray]="circumference" [attr.stroke-dashoffset]="offset()" transform="rotate(-90 50 50)" />
      }
    </svg>
    <span class="ring__label" aria-hidden="true">
      <strong #number></strong>
      @if (caption()) {
        <small>{{ caption() }}</small>
      }
    </span>
  `,
  styles: `
    :host { position: relative; display: inline-grid; place-items: center; flex: none; }
    svg { display: block; }
    .ring__track { stroke: var(--surface-sunken); }
    .ring__value { stroke: var(--tone-fg, var(--primary)); transition: stroke-dashoffset var(--dur-slow) var(--ease); animation: ring-draw 1100ms var(--ease) both; }
    @keyframes ring-draw { from { stroke-dashoffset: 263.9; } }
    :host([data-tone='warning']) .ring__value { stroke: var(--warning); }
    .ring__label { position: absolute; inset: 0; display: grid; place-content: center; text-align: center; line-height: 1.05; }
    strong { font-size: var(--ring-font, 1rem); font-weight: 700; font-variant-numeric: tabular-nums; letter-spacing: -0.02em; }
    small { margin-top: 2px; font-size: 10px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.06em; color: var(--text-subtle); }
  `,
  host: { '[attr.data-tone]': 'tone()', '[style.--ring-font]': 'fontSize()' }
})
export class ScoreRing {
  readonly value = input<number | null | undefined>(null);
  readonly size = input(64);
  readonly stroke = input(9);
  readonly caption = input('');
  readonly showGrade = input(false);
  readonly radius = 42;
  readonly circumference = 2 * Math.PI * 42;
  readonly tone = computed(() => scoreTone(this.value()));
  readonly offset = computed(() => this.circumference * (1 - Math.max(0, Math.min(100, this.value() ?? 0)) / 100));
  private readonly label = viewChild.required<ElementRef<HTMLElement>>('number');
  /** What the number shows right now (so a changed score counts from where it is, not from zero). */
  private current = 0;

  constructor() {
    effect((onCleanup) => {
      const element = this.label().nativeElement;
      const target = this.value();
      const grade = this.showGrade();
      const size = this.size();
      if (grade || target === null || target === undefined) {
        element.textContent = grade ? qualityGrade(target).grade : formatScore(target);
        this.current = target ?? 0;
        return;
      }
      const write = (value: number) => {
        this.current = value;
        element.textContent = size < 56 ? String(Math.round(value)) : formatScore(value);
      };
      onCleanup(animateNumber(untracked(() => this.current), target, COUNT_UP_MS, write));
    });
  }

  readonly fontSize = computed(() => `${Math.round(this.size() * (this.showGrade() ? 0.34 : 0.24))}px`);
  readonly ariaLabel = computed(() => this.showGrade()
    ? `Quality grade ${qualityGrade(this.value()).grade}, score ${formatScore(this.value())} out of 100`
    : `Quality score ${formatScore(this.value())} out of 100`);
}

@Component({
  selector: 'app-empty-state',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="empty-state" [class.empty-state--compact]="compact()">
      <div class="empty-state__art"><app-icon [name]="icon()" [size]="28" /></div>
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
  readonly icon = input<IconName>('database');
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

/** Small info button that shows an explanation on hover or keyboard focus. */
@Component({
  selector: 'app-info-tip',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="info-tip">
      <button type="button" class="info-tip__button" [attr.aria-label]="label()" [attr.aria-describedby]="id"><app-icon name="info" [size]="15" /></button>
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
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog #dialog class="modal" [class.modal--wide]="wide()" [attr.aria-labelledby]="titleId" (cancel)="onCancel($event)" (click)="onBackdrop($event)">
      <div class="modal__panel">
        <header class="modal__header">
          <h2 [id]="titleId">{{ title() }}</h2>
          <button type="button" class="btn btn--ghost btn--icon btn--sm" aria-label="Close" (click)="closed.emit()"><app-icon name="x" /></button>
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
        element.classList.remove('modal--closing');
        element.showModal?.();
      } else if (!this.open() && element.open) {
        this.dismiss(element);
      }
    });
  }

  /** Plays the closing animation, then closes the dialog (at once with reduced motion). */
  private dismiss(element: HTMLDialogElement): void {
    const reduced = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
    if (reduced || element.classList.contains('modal--closing')) {
      element.close();
      return;
    }
    element.classList.add('modal--closing');
    let finished = false;
    const finish = () => {
      if (finished) {
        return;
      }
      finished = true;
      element.removeEventListener('animationend', onEnd);
      element.classList.remove('modal--closing');
      if (element.open && !this.open()) {
        element.close();
      }
    };
    const onEnd = (event: Event) => {
      if (event.target === element) {
        finish();
      }
    };
    element.addEventListener('animationend', onEnd);
    setTimeout(finish, 320);
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
  imports: [Modal, FormsModule, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-modal [open]="open()" [title]="title()" [busy]="busy()" (closed)="cancelled.emit()">
      @if (danger()) {
        <div class="callout" data-tone="danger"><app-icon name="alert" /><span>{{ message() }}</span></div>
      } @else {
        <p class="confirm__message">{{ message() }}</p>
      }
      @if (requireText()) {
        <label class="field">
          <span class="field__label">Type <code>{{ requireText() }}</code> to confirm</span>
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
  icon?: IconName;
  count?: number | null;
  shortcut?: string;
}

/** Router-driven tabs with arrow-key navigation (WAI-ARIA tabs pattern). */
@Component({
  selector: 'app-tabs',
  imports: [RouterLink, Icon],
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
          [attr.aria-keyshortcuts]="tab.shortcut || null"
          [attr.title]="tab.shortcut ? tab.label + ' (' + tab.shortcut + ')' : null"
          [class.tabs__tab--active]="tab.id === active()"
        >
          @if (tab.icon) {
            <app-icon [name]="tab.icon" [size]="16" />
          }
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
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toasts" role="status" aria-live="polite" aria-relevant="additions">
      @for (toast of toasts.toasts(); track toast.id) {
        <div class="toast" animate.leave="toast--leave" [attr.data-tone]="toast.tone === 'error' ? 'danger' : toast.tone">
          <app-icon [name]="icon(toast.tone)" [size]="20" />
          <p>{{ toast.message }}</p>
          <button type="button" class="btn btn--ghost btn--icon btn--sm" aria-label="Dismiss" (click)="toasts.dismiss(toast.id)"><app-icon name="x" [size]="16" /></button>
        </div>
      }
    </div>
  `
})
export class ToastHost {
  readonly toasts = inject(ToastService);

  icon(tone: ToastTone): IconName {
    return tone === 'success' ? 'checkCircle' : tone === 'error' ? 'xCircle' : tone === 'warning' ? 'alert' : 'info';
  }
}
