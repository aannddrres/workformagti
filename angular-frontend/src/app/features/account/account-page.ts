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

  protected permissionLabel(permission: string): string {
    return ({
      'system.audit': 'აუდიტის ნახვა',
      'articles.view': 'ცოდნის ბაზის ნახვა',
      'articles.edit': 'სტატიების რედაქტირება',
      'articles.publish': 'სტატიების გამოქვეყნება',
      'articles.archive': 'სტატიების არქივირება',
      'videos.archive': 'ვიდეოების არქივირება',
      'compliance.assign': 'გაცნობის დავალებების მართვა',
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
}
