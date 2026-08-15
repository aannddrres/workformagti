import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { Observable, of, shareReplay, tap, catchError } from 'rxjs';
import { UsersService } from '../services/users.service';
import { CurrentUserProfile } from '../models/user';
import { AuthService } from './auth.service';

/**
 * The signed-in user's server-side profile, fetched once and shared.
 *
 * AuthService only knows what the JWT carries — `sub` and `role`. Permissions
 * live on the profile (GET /api/users/me), and one of them,
 * `can_view_audit_log`, is shipped by the backend specifically so the UI can
 * decide what to show (UserController.java:92). Nothing in the app read it,
 * so it travelled on every profile response and changed nothing.
 *
 * role.guard.ts:15-19 documented the gap directly: the audit panel's
 * permission-based exception "isn't modeled here yet -- that needs a real
 * user-profile fetch beyond the JWT's `role` claim". This is that fetch.
 */
@Injectable({ providedIn: 'root' })
export class UserProfileService {
  private readonly usersService = inject(UsersService);
  private readonly auth = inject(AuthService);

  private readonly _profile = signal<CurrentUserProfile | null>(null);
  readonly profile = this._profile.asReadonly();

  /**
   * Backend-granted permission to read the audit trail. MANAGER holds
   * `system.audit` by default (Permission.java:45) and gets a
   * department-scoped view (AuditLogController:205-207); this flag is the
   * server's own answer, so the UI does not have to re-derive it from roles.
   */
  readonly canViewAuditLog = computed(() => this._profile()?.can_view_audit_log === true);

  /** Single-flight: a guard and the shell may both ask before the first resolves. */
  private request?: Observable<CurrentUserProfile | null>;

  constructor() {
    // Signing out must not leave the next user looking at the previous one's
    // permissions. Keyed on the auth signal rather than a call from
    // AuthService, so no code path can forget to clear it.
    effect(() => {
      if (this.auth.currentUser() === null) {
        this.clear();
      }
    });
  }

  ensureLoaded(): Observable<CurrentUserProfile | null> {
    const loaded = this._profile();
    if (loaded) {
      return of(loaded);
    }
    this.request ??= this.usersService.me().pipe(
      tap((profile) => this._profile.set(profile)),
      // A failed profile fetch must not hard-fail navigation; the caller
      // treats null as "no permission", which fails closed.
      catchError(() => of(null)),
      shareReplay({ bufferSize: 1, refCount: false })
    );
    return this.request;
  }

  private clear(): void {
    this._profile.set(null);
    this.request = undefined;
  }
}
