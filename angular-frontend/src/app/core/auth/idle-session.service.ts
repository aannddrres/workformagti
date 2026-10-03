import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { AuthService } from './auth.service';
import { LoginPage } from './login-page';
import { environment } from '../../../environments/environment';

/**
 * Was hard-coded at 30 minutes. That is the right shared-workstation default
 * and it stays the default; it comes from the build environment
 * (`environment.idleLimitMinutes`), which must match the backend's own
 * session idle limit.
 */
const IDLE_LIMIT_MS = environment.idleLimitMinutes * 60 * 1000;
const WARNING_MS = 2 * 60 * 1000;
const HEARTBEAT_THROTTLE_MS = 60 * 1000;

/**
 * The newest activity in ANY of this browser's tabs (QA round 5, owner
 * 2026-10-03). Each tab kept its own clock, so a tab left open in the
 * background reached 30 minutes, signed the person out on the server, and
 * took the tab they were working in with it. Every tab now writes its
 * activity here and reads the newest before deciding it is idle.
 * localStorage can be missing or refuse writes; then each tab falls back to
 * its own clock, which is how it behaved before.
 */
export const SHARED_ACTIVITY_KEY = 'magti_last_activity';
const SHARE_THROTTLE_MS = 5 * 1000;

function readSharedActivity(): number {
  try {
    const value = Number(localStorage.getItem(SHARED_ACTIVITY_KEY));
    return Number.isFinite(value) ? value : 0;
  } catch {
    return 0;
  }
}

function writeSharedActivity(at: number): void {
  try {
    localStorage.setItem(SHARED_ACTIVITY_KEY, String(at));
  } catch {
    // No shared storage: this tab keeps its own clock.
  }
}

@Injectable({ providedIn: 'root' })
export class IdleSessionService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly loginPage = inject(LoginPage);
  private readonly activityEvents = ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const;
  private timer: ReturnType<typeof setInterval> | null = null;
  private lastActivity = Date.now();
  private lastHeartbeat = 0;
  private lastShared = 0;
  private readonly activityHandler = () => this.onActivity();

  readonly warningOpen = signal(false);
  readonly secondsRemaining = signal(120);

  start(): void {
    if (this.timer) return;
    this.lastActivity = Date.now();
    this.share(true);
    for (const event of this.activityEvents) {
      window.addEventListener(event, this.activityHandler, { passive: true });
    }
    this.timer = setInterval(() => this.tick(), 1000);
    this.heartbeat();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    for (const event of this.activityEvents) {
      window.removeEventListener(event, this.activityHandler);
    }
    this.warningOpen.set(false);
  }

  continueSession(): void {
    this.lastActivity = Date.now();
    this.share(true);
    this.warningOpen.set(false);
    this.secondsRemaining.set(120);
    this.heartbeat(true);
  }

  endNow(): void {
    this.expire();
  }

  private onActivity(): void {
    // Once the two-minute warning is visible, an explicit action is required;
    // background mouse movement cannot silently extend a shared-workstation session.
    if (this.warningOpen()) return;
    this.lastActivity = Date.now();
    this.share();
    this.heartbeat();
  }

  private tick(): void {
    // Work in another tab is work: adopt its newer activity, and close this
    // tab's warning if it was raised before that work happened.
    const shared = readSharedActivity();
    if (shared > this.lastActivity) {
      this.lastActivity = shared;
      if (this.warningOpen()) {
        this.warningOpen.set(false);
        this.secondsRemaining.set(120);
      }
    }
    const idle = Date.now() - this.lastActivity;
    if (idle >= IDLE_LIMIT_MS) {
      this.expire();
      return;
    }
    if (idle >= IDLE_LIMIT_MS - WARNING_MS) {
      this.warningOpen.set(true);
      this.secondsRemaining.set(Math.max(0, Math.ceil((IDLE_LIMIT_MS - idle) / 1000)));
    }
  }

  private share(force = false): void {
    if (!force && this.lastActivity - this.lastShared < SHARE_THROTTLE_MS) return;
    this.lastShared = this.lastActivity;
    writeSharedActivity(this.lastActivity);
  }

  private heartbeat(force = false): void {
    const now = Date.now();
    if (!force && now - this.lastHeartbeat < HEARTBEAT_THROTTLE_MS) return;
    this.lastHeartbeat = now;
    this.http.post<void>('/api/auth/session/heartbeat', {}).subscribe({ error: () => void 0 });
  }

  private expire(): void {
    if (!this.timer) return;
    const returnUrl = this.router.url.startsWith('/login') ? '/' : this.router.url;
    this.stop();
    this.auth.logout().subscribe(() => {
      this.loginPage.open({ returnUrl, reason: 'session-expired' });
    });
  }
}
