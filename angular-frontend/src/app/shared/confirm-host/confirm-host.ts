import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { ConfirmService } from '../../core/notifications/confirm.service';
import { PortalDialog } from '../portal-dialog/portal-dialog';

/**
 * Renders whatever {@link ConfirmService} is currently asking. Mounted once at
 * the app root beside the toast host, so every screen gets it without wiring.
 */
@Component({
  selector: 'app-confirm-host',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, PortalDialog],
  template: `
    @if (confirmService.pending(); as request) {
      <div class="fixed inset-0 z-confirm flex items-center justify-center p-4">
        <button
          type="button"
          class="absolute inset-0 dialog-backdrop"
          [attr.aria-label]="'shared.confirm.cancel' | translate"
          (click)="confirmService.respond(false)"
        ></button>
        <div
          portalDialog
          portalDialogLabelledBy="confirm-title"
          portalDialogDescribedBy="confirm-message"
          (portalDialogClose)="confirmService.respond(false)"
          class="animate-panel-in relative z-10 w-full max-w-md rounded-lg border border-slate-200 bg-white p-6 shadow-e3 dark:border-slate-700 dark:bg-slate-900"
        >
          <h2 id="confirm-title" class="text-lg font-bold text-slate-900 dark:text-slate-50">
            {{ request.title || ('shared.confirm.title' | translate) }}
          </h2>
          <p id="confirm-message" class="mt-2 text-sm leading-6 text-slate-700 dark:text-slate-200">
            {{ request.message }}
          </p>
          @if (request.details?.length) {
            <ul class="mt-3 max-h-48 overflow-y-auto rounded-md border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-700 dark:border-slate-700 dark:bg-slate-800/60 dark:text-slate-200">
              @for (line of request.details; track $index) {
                <li class="py-0.5">{{ line }}</li>
              }
            </ul>
          }
          <div class="mt-6 flex justify-end gap-2">
            <button type="button" class="secondary-button" (click)="confirmService.respond(false)">
              {{ 'shared.confirm.cancel' | translate }}
            </button>
            <button
              type="button"
              class="primary-button"
              [class.bg-danger]="request.tone === 'danger'"
              [class.hover:bg-danger-700]="request.tone === 'danger'"
              (click)="confirmService.respond(true)"
            >
              {{ request.confirmLabel }}
            </button>
          </div>
        </div>
      </div>
    }
  `
})
export class ConfirmHost {
  protected readonly confirmService = inject(ConfirmService);
}
