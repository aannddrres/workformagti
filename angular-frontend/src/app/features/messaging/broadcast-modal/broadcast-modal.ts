import { Component, inject, output, signal } from '@angular/core';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { MessagingService } from '../../../core/services/messaging.service';
import { DEPARTMENTS, ROLES } from '../../../shared/user-roles';

/**
 * Content-admin broadcast form. POST /api/broadcast persists a real Message
 * row per matching recipient (2026-08-14 fix) -- department/role filters
 * scope who receives it, "All" (the default) reaches every active user
 * except the sender. The live SSE publish the original had is still not
 * ported (see MessagingController's javadoc); recipients see it on their
 * next inbox load, not instantly.
 */
@Component({
  selector: 'app-broadcast-modal',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './broadcast-modal.html'
})
export class BroadcastModal {
  private readonly messagingService = inject(MessagingService);
  private readonly translate = inject(TranslateService);

  readonly closed = output<void>();

  protected readonly departments = DEPARTMENTS;
  protected readonly roles = ROLES;

  protected readonly targetDepartment = signal('All');
  protected readonly targetRole = signal('All');
  protected readonly message = signal('');
  protected readonly sending = signal(false);
  protected readonly sendError = signal<string | null>(null);
  protected readonly sendSuccess = signal(false);
  protected readonly sentRecipients = signal(0);

  protected roleLabelKey(role: string): string {
    return `users.role_${role}`;
  }

  protected submit(): void {
    const text = this.message().trim();
    if (!text) {
      return;
    }
    this.sending.set(true);
    this.sendError.set(null);
    this.sendSuccess.set(false);
    this.messagingService
      .broadcast({
        message: text,
        target_department: this.targetDepartment() === 'All' ? null : this.targetDepartment(),
        target_role: this.targetRole() === 'All' ? null : this.targetRole()
      })
      .subscribe({
        next: (result) => {
          this.sending.set(false);
          this.sendSuccess.set(true);
          this.sentRecipients.set(result.recipients);
          this.message.set('');
        },
        error: (err) => {
          this.sending.set(false);
          this.sendError.set(err?.error?.detail ?? this.translate.instant('messaging.broadcast.send_error'));
        }
      });
  }

  protected close(): void {
    this.closed.emit();
  }
}
