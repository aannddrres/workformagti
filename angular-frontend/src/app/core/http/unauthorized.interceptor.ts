import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { LoginPage } from '../auth/login-page';

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
 *
 * Logout is exempt for a different reason. Since PO-20 (2026-08-31) it needs
 * a live session, so signing out of one the server has already ended answers
 * 401 -- and that 401 is the expected end of a logout, not a session dying
 * mid-task. Without the exemption this interceptor would race
 * IdleSessionService to the login screen and win, replacing
 * "?reason=session-expired" with a bare redirect and dropping the only
 * explanation the operator gets for why they are back at the login screen.
 * AuthService.logout() clears local state on that 401 by itself.
 */
export const unauthorizedInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const loginPage = inject(LoginPage);

  return next(req).pipe(
    catchError((error: unknown) => {
      const isAuthEndpoint =
        req.url.includes('/api/auth/login') || req.url.includes('/api/auth/logout');
      if (error instanceof HttpErrorResponse && error.status === 401 && !isAuthEndpoint) {
        auth.clearSession();
        const returnUrl = router.url;
        // Guard against redirect loops if a 401 arrives while already leaving.
        // An account switched off mid-session says so (JwtAuthenticationFilter,
        // owner 2026-10-02): it used to answer 403, which nothing here handled,
        // and the person sat in a shell of empty lists.
        if (!returnUrl.startsWith('/login')) {
          loginPage.open(error.error?.code === 'account_disabled'
            ? { returnUrl, reason: 'account-disabled' }
            : { returnUrl });
        }
      }
      return throwError(() => error);
    })
  );
};
