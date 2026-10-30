import { Injectable, signal } from '@angular/core';

/** UI state shared across the shell: upload modal and the mobile sidebar drawer. */
@Injectable({ providedIn: 'root' })
export class LayoutService {
  readonly uploadOpen = signal(false);
  readonly sidebarOpen = signal(false);

  openUpload(): void {
    this.sidebarOpen.set(false);
    this.uploadOpen.set(true);
  }
}
