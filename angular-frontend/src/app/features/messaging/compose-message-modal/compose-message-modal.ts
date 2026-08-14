import { Component, computed, inject, output, signal } from '@angular/core';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { forkJoin, of } from 'rxjs';
import { map, switchMap } from 'rxjs/operators';
import { AuthService } from '../../../core/auth/auth.service';
import { AdminUsersService } from '../../../core/services/admin-users.service';
import { StatsService } from '../../../core/services/stats.service';
import { MessagingService } from '../../../core/services/messaging.service';

interface Recipient {
  id: number;
  name: string;
  sublabel: string;
}

/**
 * Recipient source branches by the composer's own role, mirroring the two
 * calls TeamStatsPage already makes: SYSTEM_ADMIN can message anyone
 * (AdminUsersService.list(), system_admin-only), MANAGER is scoped to their
 * own department by StatsService.departmentDashboard() (per-manager scoping
 * already fixed there) + one groupUsers() call per group -- there is no
 * general user-search endpoint a manager can call directly.
 */
@Component({
  selector: 'app-compose-message-modal',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './compose-message-modal.html'
})
export class ComposeMessageModal {
  private readonly auth = inject(AuthService);
  private readonly adminUsersService = inject(AdminUsersService);
  private readonly statsService = inject(StatsService);
  private readonly messagingService = inject(MessagingService);
  private readonly translate = inject(TranslateService);

  readonly closed = output<void>();
  readonly sent = output<void>();

  protected readonly recipients = signal<Recipient[]>([]);
  protected readonly loadingRecipients = signal(true);
  protected readonly loadError = signal(false);
  protected readonly search = signal('');
  protected readonly selectedRecipient = signal<Recipient | null>(null);
  protected readonly content = signal('');
  protected readonly sending = signal(false);
  protected readonly sendError = signal<string | null>(null);

  protected readonly filteredRecipients = computed(() => {
    const term = this.search().trim().toLowerCase();
    if (!term) {
      return this.recipients();
    }
    return this.recipients().filter(
      (r) => r.name.toLowerCase().includes(term) || r.sublabel.toLowerCase().includes(term)
    );
  });

  constructor() {
    this.loadRecipients();
  }

  private loadRecipients(): void {
    this.loadingRecipients.set(true);
    this.loadError.set(false);

    const role = this.auth.currentUser()?.role;
    const source$ =
      role === 'admin'
        ? this.adminUsersService
            .list()
            .pipe(
              map((users) => users.map((u): Recipient => ({ id: u.id, name: u.name, sublabel: u.department ?? u.email })))
            )
        : this.statsService.departmentDashboard().pipe(
            switchMap((dashboard) => {
              const calls = dashboard.departments.flatMap((dept) =>
                dept.groups.map((group) =>
                  this.statsService.groupUsers(dept.name, group.name).pipe(
                    map((res) =>
                      res.users.map(
                        (u): Recipient => ({
                          id: u.user_id,
                          name: `${u.first_name} ${u.last_name}`.trim(),
                          sublabel: `${dept.name} — ${group.name}`
                        })
                      )
                    )
                  )
                )
              );
              return calls.length === 0 ? of([]) : forkJoin(calls).pipe(map((lists) => lists.flat()));
            })
          );

    source$.subscribe({
      next: (list) => {
        this.recipients.set(list);
        this.loadingRecipients.set(false);
      },
      error: () => {
        this.loadError.set(true);
        this.loadingRecipients.set(false);
      }
    });
  }

  protected selectRecipient(r: Recipient): void {
    this.selectedRecipient.set(r);
  }

  protected clearRecipient(): void {
    this.selectedRecipient.set(null);
  }

  protected submit(): void {
    const recipient = this.selectedRecipient();
    const text = this.content().trim();
    if (!recipient || !text) {
      return;
    }
    this.sending.set(true);
    this.sendError.set(null);
    this.messagingService.send({ user_id: recipient.id, content: text }).subscribe({
      next: () => {
        this.sending.set(false);
        this.sent.emit();
      },
      error: (err) => {
        this.sending.set(false);
        this.sendError.set(err?.error?.detail ?? this.translate.instant('messaging.compose.send_error'));
      }
    });
  }

  protected close(): void {
    this.closed.emit();
  }
}
