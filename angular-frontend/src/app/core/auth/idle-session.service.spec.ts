import { vi } from 'vitest';
import { of } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';

import { IdleSessionService } from './idle-session.service';
import { AuthService } from './auth.service';

/**
 * An operator must not be left holding a session they have walked away from.
 *
 * This is the behaviour behind "what happens on a shared workstation at the
 * end of a shift", and until now nothing asserted it -- so an edit that
 * broke the timer would have surfaced only as somebody still signed in at a
 * desk they left an hour ago. Written when the logout contract was tightened
 * (PO-20), because that decision rests on this working.
 */
describe('IdleSessionService', () => {
  const IDLE_LIMIT_MS = 30 * 60 * 1000;
  const WARNING_MS = 2 * 60 * 1000;

  let service: IdleSessionService;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.fn>;
  let logout: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    navigate = vi.fn();
    logout = vi.fn().mockReturnValue(of(void 0));

    await TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Router, useValue: { navigate, url: '/articles/113' } },
        { provide: AuthService, useValue: { logout } }
      ]
    }).compileComponents();

    vi.useFakeTimers();
    service = TestBed.inject(IdleSessionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    service.stop();
    vi.useRealTimers();
  });

  /** start() posts one immediately; drained so nothing here waits on it. */
  function drainHeartbeats(): void {
    http.match('/api/auth/session/heartbeat').forEach((request) => request.flush(null));
  }

  it('warns before the session ends, rather than at the moment it ends', () => {
    service.start();
    drainHeartbeats();

    vi.advanceTimersByTime(IDLE_LIMIT_MS - WARNING_MS);

    expect(service.warningOpen()).toBe(true);
    expect(logout).not.toHaveBeenCalled();
  });

  it('signs the operator out and returns them to the login screen after thirty idle minutes', () => {
    service.start();
    drainHeartbeats();

    vi.advanceTimersByTime(IDLE_LIMIT_MS);

    expect(logout).toHaveBeenCalled();
    // returnUrl so signing back in resumes the article they were reading;
    // reason so the login screen can say why they are looking at it.
    expect(navigate).toHaveBeenCalledWith(
      ['/login'],
      { queryParams: { returnUrl: '/articles/113', reason: 'session-expired' } }
    );
  });

  it('does not let stray activity extend a session once the warning is up', () => {
    service.start();
    drainHeartbeats();

    vi.advanceTimersByTime(IDLE_LIMIT_MS - 60 * 1000);
    expect(service.warningOpen()).toBe(true);

    // A shared desk collects stray pointer events -- somebody brushing past,
    // a page repainting under the cursor. If those counted as activity, the
    // session of whoever walked away would never end.
    window.dispatchEvent(new Event('pointerdown'));
    vi.advanceTimersByTime(60 * 1000);

    expect(logout).toHaveBeenCalled();
  });

  it('lets a deliberate answer to the warning keep the session, which is its purpose', () => {
    service.start();
    drainHeartbeats();

    vi.advanceTimersByTime(IDLE_LIMIT_MS - 60 * 1000);
    expect(service.warningOpen()).toBe(true);

    service.continueSession();
    drainHeartbeats();

    expect(service.warningOpen()).toBe(false);
    vi.advanceTimersByTime(60 * 1000);
    expect(logout).not.toHaveBeenCalled();
  });
});
