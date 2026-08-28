import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { map } from 'rxjs';

/** Redirects unauthenticated users to /login, preserving the attempted URL
 *  as a returnUrl query param so login can send them back afterward. */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return auth.restoreSession().pipe(map((user) => user
    ? true
    : router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } })));
};
