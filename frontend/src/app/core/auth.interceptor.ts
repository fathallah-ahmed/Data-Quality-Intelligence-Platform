import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { environment } from '../../environments/environment';
import { AuthService } from './auth.service';
import { ToastService } from './toast.service';

/** Adds the bearer token to API calls and sends the user back to login when it is rejected. */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const toasts = inject(ToastService);
  const isApiCall = request.url.startsWith(environment.apiBase);
  const isLogin = request.url.endsWith('/auth/login');
  const token = auth.token();

  const authorized = isApiCall && token && !isLogin
    ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : request;

  return next(authorized).pipe(
    catchError((error: unknown) => {
      if (isApiCall && !isLogin && error instanceof HttpErrorResponse && error.status === 401 && auth.isAuthenticated()) {
        toasts.warning('Your session has expired. Please sign in again.');
        auth.logout(router.url);
      }
      return throwError(() => error);
    })
  );
};
