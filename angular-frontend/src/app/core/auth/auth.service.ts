import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, finalize, map, of, shareReplay, tap } from 'rxjs';

export interface CurrentUser {
  email: string;
  role: string;
}

interface LoginResponse {
  access_token: string;
  token_type: string;
}

/**
 * Cookie-backed portal session. The JWT is accepted from the login response
 * only long enough to derive the in-memory shell identity; it is never
 * persisted in Web Storage. Reloads restore the identity through
 * GET /api/users/me while the httpOnly cookie remains the credential.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly _currentUser = signal<CurrentUser | null>(null);
  readonly currentUser = this._currentUser.asReadonly();
  readonly isAuthenticated = computed(() => this._currentUser() !== null);
  private restoreRequest?: Observable<CurrentUser | null>;

  login(email: string, password: string): Observable<CurrentUser> {
    return this.http.post<LoginResponse>('/api/auth/login', { email, password }).pipe(
      map((res) => this.decodeUser(res.access_token)),
      tap((user) => this._currentUser.set(user)),
      map((user) => user!)
    );
  }

  restoreSession(): Observable<CurrentUser | null> {
    const current = this._currentUser();
    if (current) {
      return of(current);
    }
    this.restoreRequest ??= this.http.get<{ email: string; role: string }>('/api/users/me').pipe(
      map((profile) => ({ email: profile.email, role: profile.role })),
      tap((user) => this._currentUser.set(user)),
      catchError(() => {
        this._currentUser.set(null);
        return of(null);
      }),
      finalize(() => this.restoreRequest = undefined),
      shareReplay({ bufferSize: 1, refCount: false })
    );
    return this.restoreRequest;
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', {}).pipe(
      tap(() => this.clearToken()),
      map(() => void 0),
      catchError(() => {
        // Clear client-side state even if the network call fails -- an
        // unreachable backend shouldn't trap the user in a "logged in" UI.
        this.clearToken();
        return of(void 0);
      })
    );
  }

  /**
   * Drops client-side session state without calling the backend.
   *
   * logout() posts to /api/auth/logout first, which is exactly wrong for the
   * 401 path: the token the server just rejected is the one that request
   * would carry, so it fails too and the user is left signed in locally.
   */
  clearSession(): void {
    this.clearToken();
  }

  private clearToken(): void {
    this._currentUser.set(null);
    this.restoreRequest = undefined;
  }

  /** Decodes transient login-response claims for immediate navigation only.
   *  The server remains the authority and the token is not persisted. */
  private decodeUser(token: string): CurrentUser | null {
    try {
      const payloadSegment = token.split('.')[1];
      const payload = JSON.parse(atob(payloadSegment.replace(/-/g, '+').replace(/_/g, '/')));
      if (typeof payload.exp === 'number' && Date.now() >= payload.exp * 1000) {
        return null;
      }
      if (!payload.sub || !payload.role) {
        return null;
      }
      return { email: payload.sub, role: payload.role };
    } catch {
      return null;
    }
  }
}
