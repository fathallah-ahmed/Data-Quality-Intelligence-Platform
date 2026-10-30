import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, map, tap } from 'rxjs';
import { ApiService } from './api.service';
import { LoginResponse } from './models';
import { sessionStore } from './safe-storage';

const SESSION_KEY = 'dq_session';

interface Session {
  token: string;
  expiresAt: string;
  username: string;
  roles: string[];
}

/**
 * Bearer-token session. The token lives in sessionStorage (cleared with the tab), never
 * the password or a Basic header.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly session = signal<Session | null>(readSession());

  readonly token = computed(() => this.session()?.token ?? null);
  readonly username = computed(() => this.session()?.username ?? null);
  readonly isAuthenticated = computed(() => {
    const session = this.session();
    return !!session && Date.parse(session.expiresAt) > Date.now();
  });

  login(username: string, password: string): Observable<void> {
    return this.api.login(username, password).pipe(
      tap((response: LoginResponse) => {
        if (!response.roles.includes('ADMIN')) {
          throw new Error('This account is not an administrator.');
        }
        const session: Session = {
          token: response.token,
          expiresAt: response.expiresAt,
          username: response.username,
          roles: response.roles
        };
        sessionStore.set(SESSION_KEY, JSON.stringify(session));
        this.session.set(session);
      }),
      map(() => undefined)
    );
  }

  /** Clear the session and go to the login page, remembering where the user was. */
  logout(returnUrl?: string): void {
    sessionStore.set(SESSION_KEY, null);
    this.session.set(null);
    const queryParams = returnUrl && returnUrl !== '/login' ? { returnUrl } : undefined;
    this.router.navigate(['/login'], { queryParams });
  }
}

function readSession(): Session | null {
  const raw = sessionStore.get(SESSION_KEY);
  if (!raw) {
    return null;
  }
  try {
    const session = JSON.parse(raw) as Session;
    if (!session.token || Date.parse(session.expiresAt) <= Date.now()) {
      sessionStore.set(SESSION_KEY, null);
      return null;
    }
    return session;
  } catch {
    return null;
  }
}
