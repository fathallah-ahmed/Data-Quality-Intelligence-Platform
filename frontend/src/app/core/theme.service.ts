import { DOCUMENT } from '@angular/common';
import { Injectable, computed, inject, signal } from '@angular/core';
import { localStore } from './safe-storage';

export type ThemePreference = 'system' | 'light' | 'dark';

const THEME_KEY = 'dq_theme';

/** Light / dark theme: follows the OS by default, a manual choice is remembered per browser. */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);
  private readonly media = this.document.defaultView?.matchMedia?.('(prefers-color-scheme: dark)');
  private readonly systemDark = signal(this.media?.matches ?? false);
  readonly preference = signal<ThemePreference>(readPreference());
  readonly isDark = computed(() => this.preference() === 'dark' || (this.preference() === 'system' && this.systemDark()));

  constructor() {
    this.media?.addEventListener?.('change', (event) => this.systemDark.set(event.matches));
    this.apply();
  }

  toggle(): void {
    this.preference.set(this.isDark() ? 'light' : 'dark');
    localStore.set(THEME_KEY, this.preference());
    this.apply();
  }

  private apply(): void {
    const root = this.document.documentElement;
    if (this.preference() === 'system') {
      root.removeAttribute('data-theme');
    } else {
      root.setAttribute('data-theme', this.preference());
    }
  }
}

function readPreference(): ThemePreference {
  const stored = localStore.get(THEME_KEY);
  return stored === 'light' || stored === 'dark' ? stored : 'system';
}
