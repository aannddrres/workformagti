import { vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';

import { unauthorizedInterceptor } from './unauthorized.interceptor';
import { AuthService } from '../auth/auth.service';

/**
 * The second way an operator is put out of a session that has ended -- the
 * one that does not wait for the idle timer.
 *
 * A session can stop being valid while somebody is actively working: an
 * administrator deactivates the account, changes the role, resets the
 * password ("sign out everywhere"), or the eight-hour maximum runs out.
 * All of those reach the browser as a 401 on whatever request happens next.
 *
 * Before this interceptor existed the UI simply kept rendering: lists
 * silently empty, buttons silently doing nothing, no explanation and no way
 * back except knowing to type /login by hand (audit FE-05).
 */
describe('unauthorizedInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let navigate: ReturnType<typeof vi.fn>;
  let clearSession: ReturnType<typeof vi.fn>;

  function configure(currentUrl: string): void {
    navigate = vi.fn();
    clearSession = vi.fn();

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([unauthorizedInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: { navigate, url: currentUrl } },
        { provide: AuthService, useValue: { clearSession } }
      ]
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  }

  /** Fires a request, answers it with `status`, and swallows the rethrow. */
  function respondWith(url: string, status: number): void {
    http.get(url).subscribe({ next: () => void 0, error: () => void 0 });
    backend.expectOne(url).flush({ detail: 'nope' }, { status, statusText: 'x' });
  }

  it('sends a rejected session to the login screen, keeping where the operator was', () => {
    configure('/articles/113');

    respondWith('/api/articles/113', 401);

    expect(clearSession).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { returnUrl: '/articles/113' } });
  });

  it('leaves other failures alone', () => {
    configure('/articles/113');

    // A 403 means "signed in, not allowed" -- the screen's own message. A 500
    // is a server fault. Treating either as a dead session would sign people
    // out over a permission check or a bad deploy.
    respondWith('/api/articles/113', 403);
    respondWith('/api/articles/113', 500);

    expect(navigate).not.toHaveBeenCalled();
    expect(clearSession).not.toHaveBeenCalled();
  });

  it('does not bounce a failed login, whose 401 is a wrong password', () => {
    configure('/login');

    respondWith('/api/auth/login', 401);

    expect(navigate)
      .not.toHaveBeenCalled();
  });

  it('does not bounce a logout, whose 401 IS the logout succeeding as far as the user is concerned', () => {
    // PO-20 made logout require a live session, so signing out of one the
    // server has already ended answers 401. IdleSessionService is mid-flight
    // to /login with reason=session-expired at that moment; a redirect from
    // here would race it and drop the only explanation the operator gets.
    configure('/articles/113');

    respondWith('/api/auth/logout', 401);

    expect(navigate).not.toHaveBeenCalled();
  });

  it('does not redirect to the login screen from the login screen', () => {
    configure('/login');

    respondWith('/api/users/me', 401);

    expect(clearSession).toHaveBeenCalled();
    expect(navigate)
      .not.toHaveBeenCalled();
  });
});
