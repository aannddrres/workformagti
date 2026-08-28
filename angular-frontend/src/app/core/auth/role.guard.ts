import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Route-data-driven role gate, mirroring the inline role checks in
 * static/js/app-router.js's switchMainPage/switchAdmin:
 * - `allowRoles`: only these roles may enter (e.g. page-admin: ['admin']).
 * - `denyRoles`: these roles are blocked even though the route has no
 *   `allowRoles` (e.g. page-reading is operator-only -- management roles
 *   are exempt, mirrors MANAGEMENT_ROLES in compliance_utils.py).
 *
 * Route data example: `data: { allowRoles: ['admin', 'manager'] }`.
 *
 * NOTE: the audit sub-panel's extra `can_view_audit_log` permission-based
 * exception (content_admin reaches it via a DB-backed permission, not just
 * role) isn't modeled here yet -- that needs a real user-profile fetch
 * beyond the JWT's `role` claim, which belongs with the Audit domain's
 * actual page build in Phase 3c, not this generic shell-level guard.
 */
export function roleGuard(
  allowRoles?: readonly string[],
  denyRoles?: readonly string[]
): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    const router = inject(Router);
    const user = auth.currentUser();

    if (!user) {
      return router.createUrlTree(['/login']);
    }
    if (allowRoles && !allowRoles.includes(user.role)) {
      return router.createUrlTree(['/']);
    }
    if (denyRoles && denyRoles.includes(user.role)) {
      return router.createUrlTree(['/']);
    }
    return true;
  };
}
