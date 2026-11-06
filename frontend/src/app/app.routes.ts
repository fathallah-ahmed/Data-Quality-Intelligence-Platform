import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth.guard';

export const routes: Routes = [
  // Public front page: no guard, so it can be seen without signing in.
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () => import('./features/landing/landing.page').then((m) => m.LandingPage)
  },
  {
    path: 'login',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/login/login.page').then((m) => m.LoginPage)
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./features/shell/shell.page').then((m) => m.ShellPage),
    children: [
      {
        path: 'datasets',
        loadComponent: () => import('./features/datasets/datasets-home.page').then((m) => m.DatasetsHomePage)
      },
      {
        path: 'datasets/:datasetId',
        loadComponent: () => import('./features/datasets/datasets-home.page').then((m) => m.DatasetsHomePage)
      },
      { path: 'datasets/:datasetId/versions/:versionId', pathMatch: 'full', redirectTo: 'datasets/:datasetId/versions/:versionId/overview' },
      {
        path: 'datasets/:datasetId/versions/:versionId/report',
        loadComponent: () => import('./features/report/report.page').then((m) => m.ReportPage)
      },
      {
        path: 'datasets/:datasetId/versions/:versionId/:tab',
        loadComponent: () => import('./features/version/version.page').then((m) => m.VersionPage)
      }
    ]
  },
  { path: '**', redirectTo: 'datasets' }
];
