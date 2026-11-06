import { ChangeDetectionStrategy, Component, computed, effect, input, signal, untracked } from '@angular/core';

/**
 * Counts from one number to another, calling `onFrame` with each intermediate value (ease-out). Returns a function
 * that stops the animation. With reduced motion, or when there is nothing to animate, it jumps straight to the end.
 */
export function animateNumber(from: number, to: number, durationMs: number, onFrame: (value: number) => void): () => void {
  const reduced = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
  if (reduced || from === to || typeof requestAnimationFrame === 'undefined') {
    onFrame(to);
    return () => undefined;
  }
  const started = performance.now();
  let frame = 0;
  const step = () => {
    // The clock is read here rather than taken from the frame's timestamp, whose origin differs between environments.
    const progress = Math.max(0, Math.min(1, (performance.now() - started) / durationMs));
    onFrame(from + (to - from) * (1 - Math.pow(1 - progress, 3)));
    if (progress < 1) {
      frame = requestAnimationFrame(step);
    }
  };
  frame = requestAnimationFrame(step);
  return () => cancelAnimationFrame(frame);
}

/** A number that counts up when it appears and whenever it changes (screen readers get the final value). */
@Component({
  selector: 'app-count-up',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span aria-hidden="true">{{ text() }}</span><span class="sr-only">{{ final() }}</span>`,
  styles: `:host { font-variant-numeric: tabular-nums; }`
})
export class CountUp {
  readonly value = input.required<number>();
  readonly decimals = input(0);
  readonly durationMs = input(900);
  private readonly shown = signal(0);

  readonly text = computed(() => format(this.shown(), this.decimals()));
  readonly final = computed(() => format(this.value(), this.decimals()));

  constructor() {
    effect((onCleanup) => {
      const target = this.value();
      const from = untracked(() => this.shown());
      onCleanup(animateNumber(from, target, this.durationMs(), (value) => this.shown.set(value)));
    });
  }
}

function format(value: number, decimals: number): string {
  return value.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}
