import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { AuthService } from './auth.service';

const IDLE_LIMIT_MS = 30 * 60 * 1000;
const WARNING_MS = 2 * 60 * 1000;
const HEARTBEAT_THROTTLE_MS = 60 * 1000;

@Injectable({ providedIn: 'root' })
export class IdleSessionService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly activityEvents = ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const;
  private timer: ReturnType<typeof setInterval> | null = null;
  private lastActivity = Date.now();
  private lastHeartbeat = 0;
  private readonly activityHandler = () => this.onActivity();

  readonly warningOpen = signal(false);
  readonly secondsRemaining = signal(120);

  start(): void {
    if (this.timer) return;
    this.lastActivity = Date.now();
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
    this.heartbeat();
  }

  private tick(): void {
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
      this.router.navigate(['/login'], { queryParams: { returnUrl, reason: 'session-expired' } });
    });
  }
}
