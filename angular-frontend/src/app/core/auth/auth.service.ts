import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, map, of, tap } from 'rxjs';

export interface CurrentUser {
  email: string;
  role: string;
}

interface LoginResponse {
  access_token: string;
  token_type: string;
}

const TOKEN_KEY = 'magti_token';

/**
 * Port of static/js's Auth object. The real credential enforced by the
 * backend is an httpOnly cookie (see AuthController.accessTokenCookie) --
 * this service stores a second, JS-readable copy of the same token
 * (localStorage, key `magti_token`, matching the existing Python frontend's
 * convention) purely so the UI can read claims (role) and attach a Bearer
 * header, without needing cross-origin cookies during Angular-dev-server
 * development (see proxy.conf.json for why that's avoided for now).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly _currentUser = signal<CurrentUser | null>(this.readUserFromStoredToken());
  readonly currentUser = this._currentUser.asReadonly();
  readonly isAuthenticated = computed(() => this._currentUser() !== null);

  login(email: string, password: string): Observable<CurrentUser> {
    return this.http.post<LoginResponse>('/api/auth/login', { email, password }).pipe(
      tap((res) => this.storeToken(res.access_token)),
      map(() => this._currentUser()!)
    );
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

  getToken(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  }

  private storeToken(token: string): void {
    localStorage.setItem(TOKEN_KEY, token);
    this._currentUser.set(this.decodeUser(token));
  }

  private clearToken(): void {
    localStorage.removeItem(TOKEN_KEY);
    this._currentUser.set(null);
  }

  private readUserFromStoredToken(): CurrentUser | null {
    const token = this.getToken();
    if (!token) {
      return null;
    }
    const user = this.decodeUser(token);
    if (!user) {
      localStorage.removeItem(TOKEN_KEY);
    }
    return user;
  }

  /** Decodes the JWT payload client-side (no signature verification --
   *  the server is the actual authority; this is only for reading claims
   *  to drive the UI, matching the existing Python frontend's approach). */
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
