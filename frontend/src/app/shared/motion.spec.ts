import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot } from '@angular/router';
import { routePath, skipSamePage } from '../core/view-transitions';
import { ScoreRing } from './components';
import { CountUp, Reveal, animateNumber, prefersReducedMotion } from './motion';

function reducedMotion(value: boolean): void {
  (globalThis as { matchMedia?: unknown }).matchMedia = (query: string) => ({
    matches: value && query.includes('prefers-reduced-motion'),
    addEventListener: () => undefined,
    removeEventListener: () => undefined
  });
}

function snapshot(path: string, child?: ActivatedRouteSnapshot): ActivatedRouteSnapshot {
  return { url: path ? path.split('/').map((segment) => ({ path: segment })) : [], firstChild: child ?? null } as unknown as ActivatedRouteSnapshot;
}

afterEach(() => {
  delete (globalThis as { matchMedia?: unknown }).matchMedia;
  delete (globalThis as { IntersectionObserver?: unknown }).IntersectionObserver;
});

describe('page transitions', () => {
  it('reads the path of the deepest active route', () => {
    expect(routePath(snapshot('', snapshot('datasets/3/versions/4', snapshot('graph'))))).toBe('datasets/3/versions/4/graph');
    expect(routePath(null)).toBe('');
  });

  it('animates a change of page but not a change of query parameters', () => {
    const skipped: boolean[] = [];
    const run = (from: string, to: string) => {
      let skippedNow = false;
      skipSamePage({ transition: { skipTransition: () => (skippedNow = true) }, from: snapshot(from), to: snapshot(to) } as never);
      skipped.push(skippedNow);
    };
    run('datasets/3/versions/4/tables', 'datasets/3/versions/4/graph');   // another tab: animate
    run('datasets/3/versions/4/tables', 'datasets/3/versions/4/tables');  // ?table=orders to ?table=lines: do not
    expect(skipped).toEqual([false, true]);
  });
});

describe('animateNumber', () => {
  it('goes straight to the end with reduced motion', () => {
    reducedMotion(true);
    expect(prefersReducedMotion()).toBe(true);
    const seen: number[] = [];
    animateNumber(0, 40, 900, (value) => seen.push(value));
    expect(seen).toEqual([40]);
  });
});

@Component({
  selector: 'app-motion-host',
  imports: [Reveal, CountUp, ScoreRing],
  template: `
    <p appReveal [appRevealDelay]="2" id="reveal">hello</p>
    <app-count-up [value]="1234" id="count" />
    <app-score-ring [value]="92" [size]="120" id="ring" />
  `
})
class MotionHost {}

describe('Reveal, CountUp and ScoreRing', () => {
  it('shows a revealed element at once when nothing can observe it', () => {
    reducedMotion(false);
    const fixture = TestBed.createComponent(MotionHost);
    fixture.detectChanges();
    const element = fixture.nativeElement.querySelector('#reveal') as HTMLElement;
    expect(element.classList.contains('reveal')).toBe(true);
    expect(element.classList.contains('reveal--in')).toBe(true);
    expect(element.style.getPropertyValue('--reveal-i')).toBe('2');
  });

  it('reveals an element when it scrolls into view, once', () => {
    reducedMotion(false);
    let callback: ((entries: { isIntersecting: boolean }[]) => void) | undefined;
    let disconnected = 0;
    (globalThis as { IntersectionObserver?: unknown }).IntersectionObserver = class {
      constructor(handler: typeof callback) {
        callback = handler;
      }
      observe(): void {}
      disconnect(): void {
        disconnected++;
      }
    };
    const fixture = TestBed.createComponent(MotionHost);
    fixture.detectChanges();
    const element = fixture.nativeElement.querySelector('#reveal') as HTMLElement;
    expect(element.classList.contains('reveal--in')).toBe(false);

    callback?.([{ isIntersecting: true }]);
    expect(element.classList.contains('reveal--in')).toBe(true);
    expect(disconnected).toBeGreaterThan(0);
  });

  it('writes the final numbers at once with reduced motion, without a change-detection pass per frame', () => {
    reducedMotion(true);
    const fixture = TestBed.createComponent(MotionHost);
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('#count .count')?.textContent).toBe('1,234');
    expect(page.querySelector('#ring strong')?.textContent).toBe('92.0');
    // Screen readers get the figure whatever the animation is doing.
    expect(page.querySelector('#count .sr-only')?.textContent).toBe('1,234');
    expect(page.querySelector('#ring svg')?.getAttribute('aria-label')).toContain('92.0');
  });

  it('counts up to the value when motion is allowed', async () => {
    reducedMotion(false);
    const fixture = TestBed.createComponent(MotionHost);
    fixture.detectChanges();
    const count = fixture.nativeElement.querySelector('#count .count') as HTMLElement;
    await new Promise((resolve) => setTimeout(resolve, 1300));
    expect(count.textContent).toBe('1,234');
  });
});
