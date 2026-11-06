import { ChangeDetectionStrategy, Component, Directive, ElementRef, computed, effect, inject, input, untracked } from '@angular/core';

/** True when the visitor asked the system for less motion. */
export function prefersReducedMotion(): boolean {
  return globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
}

/**
 * Counts from one number to another, calling `onFrame` with each intermediate value (ease-out). Returns a function
 * that stops the animation. With reduced motion, or when there is nothing to animate, it jumps straight to the end.
 */
export function animateNumber(from: number, to: number, durationMs: number, onFrame: (value: number) => void): () => void {
  if (prefersReducedMotion() || from === to || typeof requestAnimationFrame === 'undefined') {
    onFrame(to);
    return () => undefined;
  }
  const started = performance.now();
  let frame = 0;
  const step = () => {
    // The clock is read here rather than taken from the frame's timestamp, whose origin differs between environments.
    const progress = Math.max(0, Math.min(1, (performance.now() - started) / durationMs));
    onFrame(from + (to - from) * (1 - Math.pow(1 - progress, 4)));
    if (progress < 1) {
      frame = requestAnimationFrame(step);
    }
  };
  frame = requestAnimationFrame(step);
  return () => cancelAnimationFrame(frame);
}

/**
 * A number that counts up when it appears and whenever it changes. Each frame writes the text node directly,
 * so counting several numbers at once never starts a change-detection pass; screen readers get the final value.
 */
@Component({
  selector: 'app-count-up',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span aria-hidden="true" class="count"></span><span class="sr-only">{{ final() }}</span>`,
  styles: `:host { font-variant-numeric: tabular-nums; }`
})
export class CountUp {
  readonly value = input.required<number>();
  readonly decimals = input(0);
  readonly durationMs = input(900);
  readonly final = computed(() => format(this.value(), this.decimals()));
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private current = 0;

  constructor() {
    effect((onCleanup) => {
      const target = this.value();
      const decimals = this.decimals();
      const visual = this.host.nativeElement.querySelector('.count');
      if (!visual) {
        return;
      }
      const write = (value: number) => {
        this.current = value;
        visual.textContent = format(value, decimals);
      };
      onCleanup(animateNumber(untracked(() => this.current), target, this.durationMs(), write));
    });
  }
}

function format(value: number, decimals: number): string {
  return value.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}

/**
 * Reveals an element when it scrolls into view: it fades and rises a little, once. Only opacity and transform
 * change, so the browser can run it on the compositor. `appRevealDelay` staggers a row of elements (in steps of
 * 70 ms). Without IntersectionObserver, or with reduced motion, the element is simply shown.
 */
@Directive({
  selector: '[appReveal]',
  host: { class: 'reveal', '[style.--reveal-i]': 'delay()' }
})
export class Reveal {
  readonly delay = input(0, { alias: 'appRevealDelay' });
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;

  constructor() {
    effect((onCleanup) => {
      if (prefersReducedMotion() || typeof IntersectionObserver === 'undefined') {
        this.element.classList.add('reveal--in');
        return;
      }
      const observer = new IntersectionObserver(
        (entries) => {
          if (entries.some((entry) => entry.isIntersecting)) {
            this.element.classList.add('reveal--in');
            observer.disconnect();
          }
        },
        { rootMargin: '0px 0px -8% 0px', threshold: 0.12 }
      );
      observer.observe(this.element);
      onCleanup(() => observer.disconnect());
    });
  }
}

/** Tells which element of a list is the one most in view (the section a navigation highlights). */
export function watchSections(ids: string[], onActive: (id: string) => void): () => void {
  if (typeof IntersectionObserver === 'undefined') {
    return () => undefined;
  }
  const visible = new Map<string, number>();
  const observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        visible.set(entry.target.id, entry.isIntersecting ? entry.intersectionRatio : 0);
      }
      const best = [...visible.entries()].sort((a, b) => b[1] - a[1])[0];
      if (best && best[1] > 0) {
        onActive(best[0]);
      }
    },
    { rootMargin: '-30% 0px -50% 0px', threshold: [0, 0.25, 0.5, 0.75, 1] }
  );
  for (const id of ids) {
    const element = globalThis.document?.getElementById(id);
    if (element) {
      observer.observe(element);
    }
  }
  return () => observer.disconnect();
}

