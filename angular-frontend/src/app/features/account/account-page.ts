import { Component, computed, inject, signal } from '@angular/core';
import { UserProfileService } from '../../core/auth/user-profile.service';
import { FontScaleService } from '../../core/accessibility/font-scale.service';
import { ThemeService } from '../../core/theme/theme.service';
import { ComplianceService } from '../../core/services/compliance.service';
import { MyProgress, MyReading } from '../../core/models/compliance';
import { formatKaDateTime } from '../../shared/ka-date';
import { formatDepartmentLabel } from '../../shared/department-badge';
import { BroadcastBanner } from '../../shared/broadcast-banner/broadcast-banner';

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

  protected readonly activeTab = signal<AccountTab>('profile');
  protected readonly progress = signal<MyProgress | null>(null);
  protected readonly readings = signal<MyReading[]>([]);
  protected readonly loadingEvidence = signal(true);

  protected readonly overdueReadings = computed(() => this.readings().filter((item) => item.is_overdue));
  protected readonly completedReadings = computed(() => this.readings().filter((item) => item.status === 'read'));

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
}
