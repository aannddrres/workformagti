import { Injectable, effect, inject, signal } from '@angular/core';
import { Observable, of, shareReplay, tap, catchError } from 'rxjs';
import { UsersService } from '../services/users.service';
import { CurrentUserProfile, EffectiveAccess } from '../models/user';
import { AuthService } from './auth.service';

/**
 * The signed-in user's server-side profile and effective access, each fetched
 * once and shared by every consumer of that concern.
 *
 * AuthService only knows the JWT claims. Authorization comes from
 * GET /api/me/effective-access, and both guards and navigation read this
 * service's one cache so they cannot disagree. GET /api/users/me remains the
 * display/profile source and does not make UI access decisions.
 */
@Injectable({ providedIn: 'root' })
export class UserProfileService {
  private readonly usersService = inject(UsersService);
  private readonly auth = inject(AuthService);

  private readonly _profile = signal<CurrentUserProfile | null>(null);
  readonly profile = this._profile.asReadonly();
  private readonly _access = signal<EffectiveAccess | null>(null);
  readonly access = this._access.asReadonly();

  /** Single-flight: a guard and the shell may both ask before the first resolves. */
  private request?: Observable<CurrentUserProfile | null>;
  private accessRequest?: Observable<EffectiveAccess | null>;

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

  ensureAccessLoaded(): Observable<EffectiveAccess | null> {
    const loaded = this._access();
    if (loaded) {
      return of(loaded);
    }
    this.accessRequest ??= this.usersService.effectiveAccess().pipe(
      tap((access) => this._access.set(access)),
      catchError(() => of(null)),
      shareReplay({ bufferSize: 1, refCount: false })
    );
    return this.accessRequest;
  }

  hasPermission(permission: string): boolean {
    const access = this._access();
    return access?.bypass === true || access?.permissions.includes(permission) === true;
  }

  private clear(): void {
    this._profile.set(null);
    this._access.set(null);
    this.request = undefined;
    this.accessRequest = undefined;
  }
}
