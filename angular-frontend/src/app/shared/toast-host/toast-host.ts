import { Component, inject } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { ToastService } from '../../core/notifications/toast.service';

/**
 * Renders whatever {@link ToastService} is holding. Mounted once at the app
 * root rather than inside the shell, so it also covers /login, which is
 * outside the shell.
 *
 * aria-live="assertive" on the container: these announce something the user's
 * action did or failed to do, which a screen reader should not have to
 * discover by wandering into the corner of the page.
 */
@Component({
  selector: 'app-toast-host',
  standalone: true,
  imports: [TranslatePipe],
  template: `
    <div
      class="pointer-events-none fixed bottom-4 right-4 z-toast flex w-[min(92vw,26rem)] flex-col gap-2"
      role="status"
      aria-live="assertive"
    >
      @for (toast of toastService.toasts(); track toast.id) {
        <div
          class="pointer-events-auto flex items-start gap-3 rounded-lg border p-3 shadow-e3"
          [class]="
            toast.kind === 'error'
              ? 'border-red-200 bg-red-50 text-red-800 dark:border-red-900 dark:bg-red-950/70 dark:text-red-300'
              : 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950/70 dark:text-emerald-300'
          "
        >
          <i
            class="fa-solid mt-0.5 shrink-0 text-sm"
            [class]="toast.kind === 'error' ? 'fa-circle-exclamation' : 'fa-circle-check'"
            aria-hidden="true"
          ></i>
          <p class="min-w-0 flex-1 break-words text-sm font-normal">{{ toast.message }}</p>
          <button
            type="button"
            (click)="toastService.dismiss(toast.id)"
            [attr.aria-label]="'shared.toast.dismiss' | translate"
            class="shrink-0 opacity-60 transition-opacity hover:opacity-100"
          >
            <i class="fa-solid fa-xmark text-xs" aria-hidden="true"></i>
          </button>
        </div>
      }
    </div>
  `
})
export class ToastHost {
  protected readonly toastService = inject(ToastService);
}
