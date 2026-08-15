import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { UserProfileService } from './user-profile.service';

/**
 * Gates a route on a backend-granted permission rather than on a role.
 *
 * roleGuard answers "which role are you", which cannot express the audit
 * trail's actual rule: MANAGER holds `system.audit` and gets a
 * department-scoped view, so the audit route is open to a role set the
 * coarse gate has no way to describe. Gating on the server's own
 * `can_view_audit_log` also means the UI stops re-deriving an authorization
 * decision the backend already made — the two cannot disagree.
 *
 * Fails closed: an unloadable profile resolves to null and the user is sent
 * home, rather than being let through on the assumption that the flag would
 * probably have been true.
 */
export const auditLogGuard: CanActivateFn = () => {
  const profiles = inject(UserProfileService);
  const router = inject(Router);

  return profiles
    .ensureLoaded()
    .pipe(map((profile) => (profile?.can_view_audit_log === true ? true : router.createUrlTree(['/']))));
};
