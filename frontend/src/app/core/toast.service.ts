import { Injectable, signal } from '@angular/core';

export type ToastTone = 'success' | 'error' | 'info' | 'warning';

export interface Toast {
  id: number;
  tone: ToastTone;
  message: string;
}

/** Short-lived notifications rendered by the toast host (aria-live region). */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private nextId = 1;
  readonly toasts = signal<Toast[]>([]);

  success(message: string): void {
    this.show('success', message, 4500);
  }

  info(message: string): void {
    this.show('info', message, 4500);
  }

  warning(message: string): void {
    this.show('warning', message, 7000);
  }

  error(message: string): void {
    this.show('error', message, 8000);
  }

  dismiss(id: number): void {
    this.toasts.update((toasts) => toasts.filter((toast) => toast.id !== id));
  }

  private show(tone: ToastTone, message: string, durationMs: number): void {
    const id = this.nextId++;
    // Same message twice in a row (e.g. repeated polling errors): keep a single toast.
    this.toasts.update((toasts) => [...toasts.filter((toast) => toast.message !== message), { id, tone, message }].slice(-4));
    setTimeout(() => this.dismiss(id), durationMs);
  }
}
