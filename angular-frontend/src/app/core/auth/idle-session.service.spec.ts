import { vi } from 'vitest';
import { of } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';

import { IdleSessionService, SHARED_ACTIVITY_KEY } from './idle-session.service';
import { AuthService } from './auth.service';
import { LoginPage } from './login-page';

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
  let open: ReturnType<typeof vi.fn>;
  let logout: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    open = vi.fn();
    logout = vi.fn().mockReturnValue(of(void 0));

    await TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Router, useValue: { url: '/articles/113' } },
        { provide: AuthService, useValue: { logout } },
        { provide: LoginPage, useValue: { open } }
      ]
    }).compileComponents();

    localStorage.removeItem(SHARED_ACTIVITY_KEY);
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
    // reason so the login screen can say why they are looking at it. Loaded
    // afresh (LoginPage), so nothing of theirs is left for whoever sits down.
    expect(open).toHaveBeenCalledWith({ returnUrl: '/articles/113', reason: 'session-expired' });
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
    const sharedAfterStray = localStorage.getItem(SHARED_ACTIVITY_KEY);
    vi.advanceTimersByTime(60 * 1000);

    // Failed intermittently in CI only (2 of 7 runs, 2026-10-03), never
    // locally. If it fails again, this says which way: a newer shared
    // activity than start() wrote means another instance answered the
    // event; a closed warning with no shared change means something else.
    expect(logout, `shared after stray=${sharedAfterStray}, shared now=${localStorage.getItem(SHARED_ACTIVITY_KEY)}, `
      + `warning=${service.warningOpen()}, now=${Date.now()}`).toHaveBeenCalled();
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
  // QA round 5 (owner, 2026-10-03): one person, two tabs. Working in one of
  // them must keep the other from signing them out -- the idle tab's sign-out
  // used to end the session the working tab was using.
  it('counts work done in another tab of the same browser', () => {
    service.start();
    drainHeartbeats();

    vi.advanceTimersByTime(20 * 60 * 1000);
    // The other tab, busy: it records its latest activity where every tab reads.
    localStorage.setItem(SHARED_ACTIVITY_KEY, String(Date.now()));
    vi.advanceTimersByTime(20 * 60 * 1000);

    expect(logout).not.toHaveBeenCalled();
    expect(service.warningOpen()).toBe(false);
  });

  it('closes its warning when the person is working in another tab', () => {
    service.start();
    drainHeartbeats();

    vi.advanceTimersByTime(IDLE_LIMIT_MS - 60 * 1000);
    expect(service.warningOpen()).toBe(true);

    localStorage.setItem(SHARED_ACTIVITY_KEY, String(Date.now()));
    vi.advanceTimersByTime(1000);

    expect(service.warningOpen()).toBe(false);
    vi.advanceTimersByTime(2 * 60 * 1000);
    expect(logout).not.toHaveBeenCalled();
  });

  it('tells the other tabs about its own activity', () => {
    service.start();
    drainHeartbeats();
    vi.advanceTimersByTime(10 * 1000);

    window.dispatchEvent(new Event('keydown'));
    drainHeartbeats();

    expect(Number(localStorage.getItem(SHARED_ACTIVITY_KEY))).toBe(Date.now());
  });
});
