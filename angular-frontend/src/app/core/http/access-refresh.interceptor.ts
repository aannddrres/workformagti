import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injector, inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { UserProfileService } from '../auth/user-profile.service';

export const REFRESH_ON_REFUSAL_MS = 5_000;

/**
 * A 403 is the server saying the person's rights are not what this screen
 * thinks they are -- most often because an administrator changed them after
 * sign-in. The server was already right (it re-reads rights on every
 * request); this makes the menu and the guards catch up on the spot instead
 * of at the next reload. unauthorizedInterceptor owns 401, the other half.
 *
 * UserProfileService is looked up only when a 403 arrives, not per request:
 * it injects AuthService, whose own requests pass through here, so an eager
 * inject() could meet it half-built at start-up.
 */
export const accessRefreshInterceptor: HttpInterceptorFn = (req, next) => {
  const injector = inject(Injector);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 403
          && !req.url.includes('/api/me/effective-access')) {
        injector.get(UserProfileService).refreshAccess(REFRESH_ON_REFUSAL_MS);
      }
      return throwError(() => error);
    })
  );
};
