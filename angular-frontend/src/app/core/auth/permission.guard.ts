import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { UserProfileService } from './user-profile.service';

/**
 * Gates a route on a backend-granted permission rather than on a role.
 *
 * roleGuard answers "which role are you", which cannot express effective
 * ALLOW/DENY overrides or SYSTEM_ADMIN bypass. The server's effective-access
 * response is the decision, so the UI never re-derives it from a role list.
 *
 * Fails closed: unloadable effective access resolves to null and the user is sent
 * home, rather than being let through on the assumption that the flag would
 * probably have been true.
 */
export function permissionGuard(permission: string): CanActivateFn {
  return () => {
    const profiles = inject(UserProfileService);
    const router = inject(Router);

    return profiles.ensureAccessLoaded().pipe(map((access) => {
      if (!access) {
        return router.createUrlTree(['/']);
      }
      return profiles.hasPermission(permission)
        ? true
        : router.createUrlTree(['/']);
    }));
  };
}

export const contentManageGuard = permissionGuard('content.manage');
export const auditLogGuard = permissionGuard('system.audit');
