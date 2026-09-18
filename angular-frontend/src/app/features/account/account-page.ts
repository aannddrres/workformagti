import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { UserProfileService } from '../../core/auth/user-profile.service';
import { FontScaleService } from '../../core/accessibility/font-scale.service';
import { ThemeService } from '../../core/theme/theme.service';
import { ComplianceService } from '../../core/services/compliance.service';
import { MyProgress, MyReading } from '../../core/models/compliance';
import { formatKaDateTime } from '../../shared/ka-date';
import { formatDepartmentLabel } from '../../shared/department-badge';
import { BroadcastBanner } from '../../shared/broadcast-banner/broadcast-banner';
import { ReminderService } from '../../core/services/reminder.service';
import { ReminderEntry, ReminderType } from '../../core/models/reminder';
import { PortalSessionApi, PortalSessionEntry } from '../../core/services/portal-session.service';

type AccountTab = 'profile' | 'evidence' | 'notifications' | 'settings';

@Component({
  selector: 'app-account-page',
  standalone: true,
  imports: [BroadcastBanner],
  templateUrl: './account-page.html'
})
export class AccountPage {
  protected readonly departmentLabel = formatDepartmentLabel;
  protected readonly profiles = inject(UserProfileService);
  protected readonly fontScale = inject(FontScaleService);
  protected readonly theme = inject(ThemeService);
  private readonly compliance = inject(ComplianceService);
  private readonly reminderService = inject(ReminderService);
  private readonly router = inject(Router);
  private readonly sessionApi = inject(PortalSessionApi);

  protected readonly activeTab = signal<AccountTab>('profile');
  protected readonly progress = signal<MyProgress | null>(null);
  protected readonly readings = signal<MyReading[]>([]);
  protected readonly loadingEvidence = signal(true);
  protected readonly reminders = signal<ReminderEntry[]>([]);
  protected readonly loadingReminders = signal(true);
  protected readonly reminderError = signal<string | null>(null);
  protected readonly reminderPage = signal(0);
  protected readonly reminderTotalPages = signal(0);
  protected readonly readingReminderId = signal<number | null>(null);
  protected readonly sessions = signal<PortalSessionEntry[]>([]);
  protected readonly sessionsLoading = signal(true);
  protected readonly sessionError = signal<string | null>(null);
  protected readonly revokingSessionId = signal<string | null>(null);

  protected readonly overdueReadings = computed(() => this.readings().filter((item) => item.is_overdue));
  protected readonly completedReadings = computed(() => this.readings().filter((item) => item.status === 'read'));
  protected readonly unreadReminderCount = computed(() => this.reminders().filter((item) => item.read_at === null).length);

  constructor() {
    this.profiles.ensureLoaded().subscribe();
    this.compliance.myProgress().subscribe({ next: (value) => this.progress.set(value) });
    this.compliance.myReadings().subscribe({
      next: (value) => {
        this.readings.set(value);
        this.loadingEvidence.set(false);
      },
      error: () => this.loadingEvidence.set(false)
    });
    this.loadReminders(true);
    this.loadSessions();
  }

  protected revokeSession(session: PortalSessionEntry): void {
    if (session.current || this.revokingSessionId()) return;
    this.revokingSessionId.set(session.id);
    this.sessionError.set(null);
    this.sessionApi.revoke(session.id).subscribe({
      next: () => {
        this.sessions.update((items) => items.filter((item) => item.id !== session.id));
        this.revokingSessionId.set(null);
      },
      error: () => {
        this.revokingSessionId.set(null);
        this.sessionError.set('სესიის დასრულება ვერ მოხერხდა.');
      }
    });
  }

  protected sessionDevice(session: PortalSessionEntry): string {
    const agent = session.user_agent ?? '';
    if (/mobile|android|iphone/i.test(agent)) return 'მობილური მოწყობილობა';
    if (/windows/i.test(agent)) return 'Windows კომპიუტერი';
    if (/macintosh|mac os/i.test(agent)) return 'Mac კომპიუტერი';
    return 'უცნობი მოწყობილობა';
  }

  protected selectTab(tab: AccountTab): void {
    this.activeTab.set(tab);
  }

  protected roleLabel(role: string | null | undefined): string {
    return ({
      admin: 'სისტემური ადმინი',
      content_admin: 'კონტენტ-ადმინი',
      manager: 'ჯგუფის უფროსი',
      operator: 'ოპერატორი'
    } as Record<string, string>)[role ?? ''] ?? 'მომხმარებელი';
  }

  /**
   * Every value PERMISSION_GROUPS can hand out needs an entry here: the
   * fallback prints the raw dotted code, so a gap shows the operator
   * `content.manage` on their own profile card. `content.manage` and
   * `stats.view` were exactly that gap -- both are content-admin/admin
   * defaults, so the two roles most likely to be demoed were the two that
   * saw it.
   */
  protected permissionLabel(permission: string): string {
    return ({
      'articles.view': 'ცოდნის ბაზის ნახვა',
      'articles.edit': 'სტატიების რედაქტირება',
      'articles.archive': 'სტატიების არქივირება',
      'videos.archive': 'ვიდეოების არქივირება',
      'content.manage': 'კონტენტის მართვა',
      'compliance.assign': 'გაცნობის დავალებების მართვა',
      'stats.view': 'სტატისტიკის ნახვა',
      'reports.export': 'ანგარიშების ექსპორტი',
      'users.manage': 'მომხმარებლების მართვა'
    } as Record<string, string>)[permission] ?? permission;
  }

  protected dateTime(value: string | null): string {
    return value ? formatKaDateTime(value) : 'ჯერ არ დაფიქსირებულა';
  }

  protected reminderTypeLabel(type: ReminderType): string {
    return ({
      ASSIGNMENT: 'ახალი დავალება',
      DUE_SOON: 'ვადა ახლოვდება',
      OVERDUE: 'ვადაგადაცილებულია',
      MANUAL: 'ჯგუფის უფროსის შეხსენება'
    } as Record<ReminderType, string>)[type];
  }

  protected loadMoreReminders(): void {
    this.loadReminders(false);
  }

  protected openReminder(reminder: ReminderEntry): void {
    if (this.readingReminderId() !== null) return;
    this.readingReminderId.set(reminder.id);
    this.reminderService.markRead(reminder.id).subscribe({
      next: (updated) => {
        this.reminders.update((items) => items.map((item) => item.id === updated.id ? updated : item));
        this.readingReminderId.set(null);
        if (updated.item_type === 'article' && updated.item_id !== null) {
          void this.router.navigate(['/article', updated.item_id]);
        } else if (updated.item_type === 'news' && updated.item_id !== null) {
          void this.router.navigate(['/news', updated.item_id]);
        }
      },
      error: () => {
        this.readingReminderId.set(null);
        this.reminderError.set('შეხსენების განახლება ვერ მოხერხდა. სცადეთ ხელახლა.');
      }
    });
  }

  private loadReminders(reset: boolean): void {
    const page = reset ? 0 : this.reminderPage() + 1;
    this.loadingReminders.set(true);
    this.reminderError.set(null);
    this.reminderService.inbox(page).subscribe({
      next: (result) => {
        this.reminders.update((items) => reset ? result.items : [...items, ...result.items]);
        this.reminderPage.set(result.page);
        this.reminderTotalPages.set(result.total_pages);
        this.loadingReminders.set(false);
      },
      error: () => {
        this.reminderError.set('შეხსენებების ჩატვირთვა ვერ მოხერხდა.');
        this.loadingReminders.set(false);
      }
    });
  }

  private loadSessions(): void {
    this.sessionApi.list().subscribe({
      next: (items) => { this.sessions.set(items); this.sessionsLoading.set(false); },
      error: () => { this.sessionsLoading.set(false); this.sessionError.set('აქტიური სესიების ჩატვირთვა ვერ მოხერხდა.'); }
    });
  }
}
