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
 * `/admin/overview` remains system-admin-only while D-8 is open.
 *
 * SYSTEM_ADMIN bypass enters overview. Any caller with `content.manage`
 * (including an explicitly granted operator) is redirected to the content
 * workspace. Everyone else fails closed at home. This redirect preserves the
 * capability boundary without deciding whether content managers may see the
 * statistics overview.
 */
export const adminOverviewGuard: CanActivateFn = () => {
  const profiles = inject(UserProfileService);
  const router = inject(Router);

  return profiles.ensureAccessLoaded().pipe(map((access) => {
    if (access?.bypass) {
      return true;
    }
    if (profiles.hasPermission('content.manage')) {
      return router.createUrlTree(['/admin', 'content']);
    }
    return router.createUrlTree(['/']);
  }));
};
