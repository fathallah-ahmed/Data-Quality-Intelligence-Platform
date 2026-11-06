import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { routes } from '../../app.routes';
import { authGuard, guestGuard } from '../../core/auth.guard';
import { AuthService } from '../../core/auth.service';
import { BENEFITS, FEATURES, LandingPage, PREVIEW_TABS, STEPS } from './landing.page';

function signedIn(value: boolean) {
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: AuthService, useValue: { isAuthenticated: () => value } }]
  });
}

describe('routes and guards', () => {
  it('serves the landing page at / without any guard, and before every protected route', () => {
    const landing = routes[0];
    expect(landing.path).toBe('');
    expect(landing.pathMatch).toBe('full');
    expect(landing.canActivate).toBeUndefined();
    expect(landing.loadComponent).toBeDefined();
  });

  it('keeps the application behind the sign-in and the sign-in page for guests', () => {
    const shell = routes.find((route) => route.path === '' && route.children);
    expect(shell?.canActivate).toEqual([authGuard]);
    expect(routes.find((route) => route.path === 'login')?.canActivate).toEqual([guestGuard]);
  });

  it('sends a visitor who is not signed in to /login and remembers where they wanted to go', () => {
    signedIn(false);
    const result = TestBed.runInInjectionContext(() => authGuard({} as never, { url: '/datasets/3/versions/4/graph' } as never));
    expect(result).toBeInstanceOf(UrlTree);
    const tree = TestBed.inject(Router).serializeUrl(result as UrlTree);
    expect(tree).toBe('/login?returnUrl=%2Fdatasets%2F3%2Fversions%2F4%2Fgraph');
  });

  it('lets a signed-in user in, and sends them from /login to the app', () => {
    signedIn(true);
    expect(TestBed.runInInjectionContext(() => authGuard({} as never, { url: '/datasets' } as never))).toBe(true);
    const guest = TestBed.runInInjectionContext(() => guestGuard({} as never, {} as never));
    expect(TestBed.inject(Router).serializeUrl(guest as UrlTree)).toBe('/datasets');
  });
});

describe('landing page', () => {
  function render(authenticated: boolean): HTMLElement {
    signedIn(authenticated);
    const fixture = TestBed.createComponent(LandingPage);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('offers to sign in when nobody is signed in', () => {
    const page = render(false);
    const links = Array.from(page.querySelectorAll('a.btn')).map((link) => link.getAttribute('href'));
    expect(links.length).toBeGreaterThan(0);
    expect(links.every((href) => href === '/login')).toBe(true);
    expect(page.textContent).not.toContain('Open the app');
  });

  it('offers to open the app when the visitor is already signed in', () => {
    const page = render(true);
    const links = Array.from(page.querySelectorAll('a.btn')).map((link) => link.getAttribute('href'));
    expect(links.every((href) => href === '/datasets')).toBe(true);
    expect(page.textContent).toContain('Open the app');
  });

  it('has the presentation, features, how-it-works and preview sections', () => {
    const page = render(false);
    for (const id of ['benefits', 'features', 'how', 'preview']) {
      expect(page.querySelector(`section#${id}`)).not.toBeNull();
    }
    expect(page.querySelectorAll('.benefit').length).toBe(BENEFITS.length);
    expect(page.querySelectorAll('.feature').length).toBe(FEATURES.length);
    expect(page.querySelectorAll('.step').length).toBe(STEPS.length);
    expect(page.querySelector('h1')?.textContent).toContain('Know if your data can be trusted');
  });

  it('switches the preview screen with its tabs', () => {
    const page = render(false);
    const tabs = Array.from(page.querySelectorAll<HTMLButtonElement>('[role="tab"]'));
    expect(tabs.length).toBe(PREVIEW_TABS.length);
    expect(tabs[0].getAttribute('aria-selected')).toBe('true');
    expect(page.querySelector('[role="tabpanel"]')?.textContent).toContain('Your tables at a glance');

    tabs[1].click();
    TestBed.tick();
    const panel = page.querySelector('[role="tabpanel"]');
    expect(tabs[1].getAttribute('aria-selected')).toBe('true');
    expect(panel?.textContent).toContain('Fix what is wrong, on a copy');
  });

  it('speaks to people, not to engineers', () => {
    const page = render(false);
    const text = (page.textContent ?? '').toLowerCase();
    // Whole words only: "assistant" and "close" are fine, "SSE" is not.
    for (const jargon of ['neo4j', 'airflow', 'cypher', 'ollama', 'spring', 'sse', 'csv', 'api', 'llm', 'jwt']) {
      expect(text).not.toMatch(new RegExp(`\\b${jargon}\\b`));
    }
  });
});
