import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from './auth.service';
import { UserProfileService } from './user-profile.service';

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
 * Capability checks deliberately live in permission.guard.ts. This helper
 * remains for the Phase 4/5 role boundaries and admin-only screens.
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
      return router.createUrlTree(['/forbidden']);
    }
    if (denyRoles && denyRoles.includes(user.role)) {
      return router.createUrlTree(['/forbidden']);
    }
    return true;
  };
}

/**
 * `/admin/overview` follows the resolved D-8 capability split.
 *
 * SYSTEM_ADMIN bypass and an explicit `stats.view` grant enter the aggregate
 * overview. A caller with only `content.manage` is redirected to the content
 * workspace. Everyone else fails closed. The ordering matters when one caller
 * holds both capabilities: statistics stays directly reachable.
 */
export const adminOverviewGuard: CanActivateFn = () => {
  const profiles = inject(UserProfileService);
  const router = inject(Router);

  return profiles.ensureAccessLoaded().pipe(map((access) => {
    if (access?.bypass || profiles.hasPermission('stats.view')) {
      return true;
    }
    if (profiles.hasPermission('content.manage')) {
      return router.createUrlTree(['/admin', 'content']);
    }
    return router.createUrlTree(['/forbidden']);
  }));
};
