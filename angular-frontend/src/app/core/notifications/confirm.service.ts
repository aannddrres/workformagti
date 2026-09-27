import { Injectable, signal } from '@angular/core';

export interface ConfirmRequest {
  /** Already-translated question. Callers own translation, as with ToastService. */
  message: string;
  title?: string;
  confirmLabel?: string;
  /** `danger` paints the confirm button as destructive. Use it for deletes. */
  tone?: 'default' | 'danger';
  /** Already-translated lines listed under the message -- who or what the answer affects. */
  details?: readonly string[];
}

interface PendingConfirm extends ConfirmRequest {
  resolve: (answer: boolean) => void;
}

/**
 * The in-app replacement for `window.confirm`.
 *
 * There were seventeen `window.confirm` calls and one `window.alert` -- on
 * deletes, bulk publishes, role moves, trash restores and every unsaved-changes
 * guard. A grey operating-system dialog in the middle of a corporate portal
 * reads as an unfinished internal tool, and it cannot be translated, themed,
 * or made to say which record is about to go.
 *
 * `admin-roles-page.ts` documented the trade-off honestly when it wrote the
 * first one: "not worth building a whole confirm-dialog component for this one
 * call site". That was true of one call site. It stopped being true somewhere
 * around the seventeenth.
 */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private readonly _pending = signal<PendingConfirm | null>(null);
  readonly pending = this._pending.asReadonly();

  ask(request: ConfirmRequest | string): Promise<boolean> {
    const normalised: ConfirmRequest =
      typeof request === 'string' ? { message: request } : request;

    // A second question while one is open would strand the first promise, and
    // a caller awaiting an answer that never comes is worse than a "no".
    this.respond(false);

    return new Promise<boolean>((resolve) => {
      this._pending.set({ ...normalised, resolve });
    });
  }

  respond(answer: boolean): void {
    const pending = this._pending();
    if (!pending) {
      return;
    }
    this._pending.set(null);
    pending.resolve(answer);
  }
}
