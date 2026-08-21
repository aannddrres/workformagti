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
export function roleGuard(allowRoles?: string[], denyRoles?: string[]): CanActivateFn {
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

/**
 * The gate on `/admin/overview`, which is system-admin-only since the
 * Phase 0 access fix.
 *
 * A plain `roleGuard(['admin'])` here bounced a content admin out of the
 * admin area altogether: `/admin` and the legacy `/admin/main` both
 * redirect to `overview`, and a refused roleGuard sends the caller to `/`.
 * So a content admin opening `/admin` landed on the dashboard rather than
 * on the content workspace they do have access to -- the plan's stated
 * behaviour (ORG_ACCESS_ARCHITECTURE_PLAN_KA.md §6).
 *
 * Redirecting instead of refusing also fixes the same problem for a
 * bookmarked `/admin/overview`. Anything other than the two admin roles is
 * still sent home; the parent route's `roleGuard` has already refused those,
 * so that branch is defence in depth rather than a reachable path.
 */
export const adminOverviewGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const user = auth.currentUser();

  if (!user) {
    return router.createUrlTree(['/login']);
  }
  if (user.role === 'admin') {
    return true;
  }
  if (user.role === 'content_admin') {
    return router.createUrlTree(['/admin', 'content']);
  }
  return router.createUrlTree(['/']);
};
