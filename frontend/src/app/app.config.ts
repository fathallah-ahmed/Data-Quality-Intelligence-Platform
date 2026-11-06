import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling, withViewTransitions } from '@angular/router';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';
import { skipSamePage } from './core/view-transitions';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideHttpClient(withInterceptors([authInterceptor])),
    // Route and query parameters are bound to component inputs (datasetId, versionId, tab, table).
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
      // Pages settle in with the View Transitions API where the browser has it (see styles.css); elsewhere they just swap.
      withViewTransitions({ skipInitialTransition: true, onViewTransitionCreated: skipSamePage })
    )
  ]
};
