import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Default workspace follows the user's primary operational role. */
export const landingGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const role = auth.currentUser()?.role;
  if (role === 'manager') return router.createUrlTree(['/manager']);
  if (role === 'content_admin') return router.createUrlTree(['/admin', 'content']);
  if (role === 'admin') return router.createUrlTree(['/admin', 'overview']);
  return true;
};
