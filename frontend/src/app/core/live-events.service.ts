import { Injectable, inject, signal } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { ApiService } from './api.service';
import { AuthService } from './auth.service';
import { LiveEvent } from './models';

export type LiveTransport = 'sse' | 'polling' | 'offline' | 'connecting';

const POLL_INTERVAL_MS = 5000;
const RECONNECT_DELAY_MS = 5000;

/**
 * Live pipeline updates. Uses the backend SSE stream (fetch, so the bearer token can be
 * sent) and falls back to polling ticks while the stream is down.
 */
@Injectable({ providedIn: 'root' })
export class LiveEventsService {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly eventsSubject = new Subject<LiveEvent>();
  private abortController?: AbortController;
  private reconnectTimer?: ReturnType<typeof setTimeout>;
  private pollTimer?: ReturnType<typeof setInterval>;
  private running = false;

  /** Pipeline events, plus a synthetic `POLL` tick every few seconds while SSE is unavailable. */
  readonly events: Observable<LiveEvent> = this.eventsSubject.asObservable();
  readonly transport = signal<LiveTransport>('connecting');

  start(): void {
    if (this.running) {
      return;
    }
    this.running = true;
    this.connect();
  }

  stop(): void {
    this.running = false;
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = undefined;
    this.stopPolling();
    this.abortController?.abort();
    this.abortController = undefined;
    this.transport.set('connecting');
  }

  private async connect(): Promise<void> {
    const token = this.auth.token();
    if (!this.running || !token || this.abortController) {
      return;
    }
    const controller = new AbortController();
    this.abortController = controller;
    try {
      const response = await fetch(this.api.eventStreamUrl(), {
        headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
        signal: controller.signal
      });
      if (!response.ok || !response.body) {
        throw new Error(`SSE stream failed with HTTP ${response.status}`);
      }
      this.stopPolling();
      this.transport.set('sse');
      // Catch up on anything that happened while the stream was down.
      this.eventsSubject.next({ type: 'RECONNECTED' });
      await this.consume(response.body, controller.signal);
      if (!controller.signal.aborted) {
        this.fallBack('polling');
      }
    } catch (error) {
      if (!controller.signal.aborted) {
        // A TypeError from fetch means the server could not be reached at all.
        this.fallBack(error instanceof TypeError ? 'offline' : 'polling');
      }
    } finally {
      if (this.abortController === controller) {
        this.abortController = undefined;
      }
    }
  }

  private fallBack(transport: LiveTransport): void {
    if (!this.running) {
      return;
    }
    this.transport.set(transport);
    this.startPolling();
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      this.connect();
    }, RECONNECT_DELAY_MS);
  }

  private startPolling(): void {
    if (!this.pollTimer) {
      this.pollTimer = setInterval(() => this.eventsSubject.next({ type: 'POLL' }), POLL_INTERVAL_MS);
    }
  }

  private stopPolling(): void {
    clearInterval(this.pollTimer);
    this.pollTimer = undefined;
  }

  private async consume(body: ReadableStream<Uint8Array>, signal: AbortSignal): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    try {
      while (!signal.aborted) {
        const { value, done } = await reader.read();
        if (done) {
          break;
        }
        buffer += decoder.decode(value, { stream: true });
        const chunks = buffer.split(/\r?\n\r?\n/);
        buffer = chunks.pop() ?? '';
        for (const chunk of chunks) {
          const event = parseSseChunk(chunk);
          if (event && event.type !== 'CONNECTED') {
            this.eventsSubject.next(event);
          }
        }
      }
    } finally {
      reader.releaseLock();
    }
  }
}

/** Parse one `event:` / `data:` block of a text/event-stream. */
export function parseSseChunk(chunk: string): LiveEvent | null {
  let eventType = 'message';
  const dataLines: string[] = [];
  for (const line of chunk.split(/\r?\n/)) {
    if (line.startsWith('event:')) {
      eventType = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart());
    }
  }
  if (dataLines.length === 0) {
    return null;
  }
  try {
    const parsed = JSON.parse(dataLines.join('\n')) as Partial<LiveEvent>;
    return { ...parsed, type: parsed.type ?? eventType };
  } catch {
    return null;
  }
}
