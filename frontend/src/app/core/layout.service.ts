import { Injectable, computed, signal } from '@angular/core';
import { localStore } from './safe-storage';

const COLLAPSED_KEY = 'dq_sidebar_collapsed';

/** UI state shared across the shell: upload modal, command palette and the sidebar. */
@Injectable({ providedIn: 'root' })
export class LayoutService {
  readonly uploadOpen = signal(false);
  readonly paletteOpen = signal(false);
  /** Mobile drawer. */
  readonly sidebarOpen = signal(false);
  /** Desktop icon rail, remembered per browser. */
  readonly sidebarCollapsed = signal(localStore.get(COLLAPSED_KEY) === 'true');
  /** Below 1024 px the sidebar is a drawer and always shows its full width. */
  readonly compact = signal(false);
  readonly railCollapsed = computed(() => this.sidebarCollapsed() && !this.compact());

  constructor() {
    const query = globalThis.matchMedia?.('(max-width: 1023px)');
    if (query) {
      this.compact.set(query.matches);
      query.addEventListener?.('change', (event) => this.compact.set(event.matches));
    }
  }

  openUpload(): void {
    this.sidebarOpen.set(false);
    this.paletteOpen.set(false);
    this.uploadOpen.set(true);
  }

  toggleCollapsed(): void {
    this.sidebarCollapsed.update((collapsed) => !collapsed);
    localStore.set(COLLAPSED_KEY, String(this.sidebarCollapsed()));
  }
}
