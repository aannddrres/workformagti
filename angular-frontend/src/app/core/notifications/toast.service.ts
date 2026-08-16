import { Injectable, signal } from '@angular/core';

export type ToastKind = 'error' | 'success';

export interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
}

/**
 * The app's one way of telling the user something happened.
 *
 * It had none. The audit counted 25 of 97 subscribe call sites giving no
 * feedback at all on failure — 14 passing `error: () => {}` and 11 with no
 * error handler — and the same class of bug has now been found and fixed
 * three separate times in this codebase. Each fix added a bespoke
 * `errorMessage` signal to one component, which is why it kept coming back:
 * there was nothing to reach for, so every author invented one or gave up.
 *
 * Deliberately not an HTTP interceptor that toasts every failed response.
 * An interceptor cannot tell a failure the component already handles (login's
 * inline "wrong password", the quiz gate's 403, ComplianceService.markRead
 * mapping failures into a result object) from one nobody is handling, so it
 * would double-report exactly where the UX is already careful. Components opt
 * in instead.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private static readonly DISMISS_AFTER_MS = 6000;

  private nextId = 1;
  private readonly _toasts = signal<Toast[]>([]);
  readonly toasts = this._toasts.asReadonly();

  /**
   * @param message already-translated text. Callers own translation because
   *   most useful messages are the backend's own Georgian `detail` string.
   */
  error(message: string): void {
    this.push('error', message);
  }

  success(message: string): void {
    this.push('success', message);
  }

  dismiss(id: number): void {
    this._toasts.update((list) => list.filter((t) => t.id !== id));
  }

  private push(kind: ToastKind, message: string): void {
    const id = this.nextId++;
    this._toasts.update((list) => [...list, { id, kind, message }]);
    setTimeout(() => this.dismiss(id), ToastService.DISMISS_AFTER_MS);
  }
}
