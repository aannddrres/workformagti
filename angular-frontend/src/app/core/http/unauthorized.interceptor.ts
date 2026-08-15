import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';

/**
 * Sends an expired or rejected session back to /login instead of leaving the
 * user in a shell that has stopped working.
 *
 * Nothing anywhere in the app handled 401 (audit FE-05): auth.interceptor
 * only attaches the token, and core/http held just the base-url interceptor.
 * So once a token expired, every request failed while the UI kept rendering
 * as if the user were signed in — lists silently empty, actions silently
 * doing nothing, with no explanation and no way back other than knowing to
 * navigate to /login by hand.
 *
 * The attempted URL is preserved as returnUrl, the query param authGuard
 * already uses, so the user resumes where they were rather than on the
 * dashboard.
 *
 * The login request itself is exempt: a wrong password is a 401 that the
 * login form reports inline, and bouncing to /login from /login would replace
 * that message with a silent reload.
 */
export const unauthorizedInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return next(req).pipe(
    catchError((error: unknown) => {
      const isAuthEndpoint = req.url.includes('/api/auth/login');
      if (error instanceof HttpErrorResponse && error.status === 401 && !isAuthEndpoint) {
        auth.clearSession();
        const returnUrl = router.url;
        // Guard against redirect loops if a 401 arrives while already leaving.
        if (!returnUrl.startsWith('/login')) {
          router.navigate(['/login'], { queryParams: { returnUrl } });
        }
      }
      return throwError(() => error);
    })
  );
};
