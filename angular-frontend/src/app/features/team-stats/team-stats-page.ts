import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Observable } from 'rxjs';
import { StatsService } from '../../core/services/stats.service';
import { ExportPollTimeoutError, ExportService } from '../../core/services/export.service';
import { CriticalOperator, DepartmentDashboard, DepartmentGroupStats, DepartmentStats, GroupMemberCompletion, LeadershipOption } from '../../core/models/stats';
import { ExportJobResponse } from '../../core/models/export';
import { ReminderService } from '../../core/services/reminder.service';
import { PortalDialog } from '../../shared/portal-dialog/portal-dialog';

type SortMode = 'name' | 'compliance';
type Tier = { bar: string; text: string };
type ExportKind = 'xlsx' | 'pdf' | 'team_stats_pdf';

/**
 * Port of page-manager + dept-dashboard.js (479 lines) -- the Executive
 * Department Dashboard. Full rewrite as Angular components, not a line
 * translation (per the migration doc's own §3d note that this file is
 * "complete rewrite, not translation").
 *
 * Two vanilla-JS mechanics deliberately NOT ported:
 * - The 60s auto-refresh timer + "Live" badge -- replaced with an explicit
 *   refresh button, the same convention already used by News/KB in this
 *   port, instead of a background poll a manager might not even want.
 * - The JS count-up number animation / requestAnimationFrame bar easing --
 *   pure visual flourish; values render immediately, bar width still
 *   transitions via a plain CSS transition on the width change.
 *
 * Preserved faithfully, including a genuine original quirk: there are THREE
 * independent percentage-coloring schemes, not one --
 * 1. `tierBar`/`tierText` (dept + group bars, and the big dept-card %):
 *    emerald >=80, red <30, else amber -- from dept-dashboard.js's tier().
 * 2. `pctColorClass` (the small % next to each group name, and every row in
 *    the group-users modal): green >=100, yellow >50, else red -- from
 *    applyPctColors()/pctColorClass(), explicitly commented there as an
 *    "alarm-fatigue fix" (a stricter bar can show emerald while the nearby
 *    number is still yellow, deliberately less alarming at a glance).
 * 3. The group-users modal's own inline progress-bar fill uses yet a third
 *    threshold pair (emerald >=100, amber >50, red else) -- kept exactly as
 *    the original, see `memberBarClass`.
 */
@Component({
  selector: 'app-team-stats-page',
  standalone: true,
  imports: [TranslatePipe, PortalDialog],
  templateUrl: './team-stats-page.html'
})
export class TeamStatsPage {
  private readonly statsService = inject(StatsService);
  private readonly exportService = inject(ExportService);
  private readonly reminderService = inject(ReminderService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly dashboard = signal<DepartmentDashboard | null>(null);
  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly sortMode = signal<SortMode>('name');
  protected readonly leadershipGroups = signal<LeadershipOption[]>([]);
  protected readonly selectedTeamId = signal<number | null>(null);
  protected readonly canExportPrimary = signal(false);

  protected readonly criticalModalOpen = signal(false);
  protected readonly criticalLoading = signal(false);
  protected readonly criticalError = signal(false);
  protected readonly criticalOperators = signal<CriticalOperator[]>([]);
  protected readonly criticalTotal = signal(0);

  protected readonly groupModalOpen = signal(false);
  protected readonly groupModalTitle = signal('');
  protected readonly groupLoading = signal(false);
  protected readonly groupError = signal(false);
  protected readonly groupUsers = signal<GroupMemberCompletion[]>([]);
  protected readonly reminderSendingUserId = signal<number | null>(null);
  protected readonly reminderFeedback = signal<Record<number, { ok: boolean; message: string }>>({});

  protected readonly exportingCsv = signal(false);
  protected readonly csvError = signal<string | null>(null);
  protected readonly asyncExport = signal<{ kind: ExportKind; status: 'processing' | 'completed' } | null>(null);
  protected readonly asyncExportError = signal<string | null>(null);

  constructor() {
    this.statsService.leadershipOptions().subscribe({
      next: (response) => {
        this.leadershipGroups.set(response.groups);
        this.selectedTeamId.set(response.defaultTeamId);
        this.canExportPrimary.set(response.canExportPrimary);
        this.load();
      },
      error: () => this.load()
    });
  }

  private load(): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.statsService.departmentDashboard(this.selectedTeamId()).subscribe({
      next: (data) => {
        this.dashboard.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set(this.translate.instant('manager.page.load_error'));
        this.loading.set(false);
      }
    });
  }

  selectTeam(value: string): void {
    const teamId = Number(value);
    if (!Number.isFinite(teamId) || teamId === this.selectedTeamId()) return;
    this.selectedTeamId.set(teamId);
    this.load();
  }

  refresh(): void {
    this.load();
  }

  toggleSort(): void {
    this.sortMode.set(this.sortMode() === 'compliance' ? 'name' : 'compliance');
  }

  sortedGroups(dept: DepartmentStats): DepartmentGroupStats[] {
    if (this.sortMode() !== 'compliance') {
      return dept.groups;
    }
    return [...dept.groups].sort((a, b) => (Number(b.compliance) || 0) - (Number(a.compliance) || 0));
  }

  tierBar(pct: number, hasReq: boolean): string {
    return this.tier(pct, hasReq).bar;
  }

  tierText(pct: number, hasReq: boolean): string {
    return this.tier(pct, hasReq).text;
  }

  private tier(pct: number, hasReq: boolean): Tier {
    if (!hasReq) {
      return { bar: 'bg-gray-300 dark:bg-zinc-600', text: 'text-gray-400 dark:text-zinc-500' };
    }
    if (pct >= 80) {
      return { bar: 'bg-emerald-500', text: 'text-emerald-600 dark:text-emerald-400' };
    }
    if (pct < 30) {
      return { bar: 'bg-brand', text: 'text-brand dark:text-red-400' };
    }
    return { bar: 'bg-amber-400', text: 'text-amber-600 dark:text-amber-400' };
  }

  pctColorClass(pct: number): string {
    if (pct >= 100) return 'text-green-600 dark:text-green-400';
    if (pct > 50) return 'text-yellow-600 dark:text-yellow-400';
    return 'text-red-600 dark:text-red-400';
  }

  memberBarClass(pct: number): string {
    if (pct >= 100) return 'bg-emerald-500';
    if (pct > 50) return 'bg-amber-400';
    return 'bg-brand';
  }

  openCriticalModal(): void {
    this.criticalModalOpen.set(true);
    this.criticalLoading.set(true);
    this.criticalError.set(false);
    this.statsService.criticalOperators(this.selectedTeamId()).subscribe({
      next: (data) => {
        this.criticalOperators.set(data.operators);
        this.criticalTotal.set(data.total);
        this.criticalLoading.set(false);
      },
      error: () => {
        this.criticalError.set(true);
        this.criticalLoading.set(false);
      }
    });
  }

  closeCriticalModal(): void {
    this.criticalModalOpen.set(false);
  }

  openGroupModal(dept: DepartmentStats, group: DepartmentGroupStats): void {
    this.groupModalOpen.set(true);
    this.groupModalTitle.set(group.name);
    this.groupLoading.set(true);
    this.groupError.set(false);
    this.statsService.groupUsers(dept.name, group.name).subscribe({
      next: (data) => {
        this.groupUsers.set(data.users);
        this.groupLoading.set(false);
      },
      error: () => {
        this.groupError.set(true);
        this.groupLoading.set(false);
      }
    });
  }

  closeGroupModal(): void {
    this.groupModalOpen.set(false);
  }

  sendReminder(userId: number, event: Event): void {
    event.stopPropagation();
    if (this.reminderSendingUserId() !== null) return;
    this.reminderSendingUserId.set(userId);
    this.reminderFeedback.update((value) => {
      const next = { ...value };
      delete next[userId];
      return next;
    });
    this.reminderService.sendManual(userId).subscribe({
      next: () => {
        this.reminderSendingUserId.set(null);
        this.reminderFeedback.update((value) => ({
          ...value, [userId]: { ok: true, message: 'ფიქსირებული შეხსენება გაიგზავნა.' }
        }));
      },
      error: (err: HttpErrorResponse) => {
        this.reminderSendingUserId.set(null);
        this.reminderFeedback.update((value) => ({
          ...value,
          [userId]: { ok: false, message: err.error?.detail ?? 'შეხსენების გაგზავნა ვერ მოხერხდა.' }
        }));
      }
    });
  }

  exportCsv(): void {
    this.exportingCsv.set(true);
    this.csvError.set(null);
    this.exportService.exportReadingsCsv().subscribe({
      next: (blob) => {
        this.exportingCsv.set(false);
        this.downloadBlob(blob, 'readings_export.csv');
      },
      error: (err: HttpErrorResponse) => {
        this.exportingCsv.set(false);
        this.handleCsvError(err);
      }
    });
  }

  exportXlsx(): void {
    this.runAsyncExport('xlsx', () => this.exportService.submitReadingsXlsx(), 'readings_export.xlsx');
  }

  exportPdf(): void {
    this.runAsyncExport('pdf', () => this.exportService.submitReadingsPdf(), 'readings_export.pdf');
  }

  exportTeamStatsPdf(): void {
    this.runAsyncExport('team_stats_pdf', () => this.exportService.submitTeamStatsPdf(), 'team_stats.pdf');
  }

  private runAsyncExport(kind: ExportKind, submit: () => Observable<ExportJobResponse>, filename: string): void {
    this.asyncExportError.set(null);
    this.asyncExport.set({ kind, status: 'processing' });
    submit().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (job) => {
        // FE-03: without takeUntilDestroyed the interval kept running after
        // the user navigated away -- every tick still firing a request and
        // still writing to a destroyed component's signals. Nothing in this
        // app unsubscribed from anything before the audit.
        this.exportService.pollUntilDone(job.job_id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: (status) => {
            if (status.status === 'failed') {
              this.asyncExport.set(null);
              this.asyncExportError.set(this.translate.instant('manager.page.export_failed'));
              return;
            }
            this.asyncExport.set({ kind, status: status.status === 'completed' ? 'completed' : 'processing' });
            if (status.status === 'completed') {
              this.exportService.download(job.job_id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: (blob) => {
                  this.downloadBlob(blob, filename);
                  this.asyncExport.set(null);
                },
                error: (err: HttpErrorResponse) => {
                  this.asyncExport.set(null);
                  // BL-09: the backend used to answer every download problem
                  // with "not ready yet", so the only honest thing the UI
                  // could say was a generic failure. 410 now means the file
                  // is gone for good — telling the user to regenerate is the
                  // difference between one more click and reloading forever.
                  this.asyncExportError.set(
                    this.translate.instant(err.status === 410 ? 'manager.page.export_expired' : 'manager.page.export_error')
                  );
                }
              });
            }
          },
          error: (err: unknown) => {
            this.asyncExport.set(null);
            // A job that never leaves "processing" used to leave the spinner
            // turning forever with no way to tell whether anything was still
            // happening. It is a distinct outcome from "the request failed",
            // and the only one where "try again" is the right advice.
            this.asyncExportError.set(
              this.translate.instant(
                err instanceof ExportPollTimeoutError ? 'manager.page.export_timeout' : 'manager.page.export_error'
              )
            );
          }
        });
      },
      error: (err: HttpErrorResponse) => {
        this.asyncExport.set(null);
        this.asyncExportError.set(
          err.status === 413 ? (err.error?.detail ?? this.translate.instant('manager.page.export_error')) : this.translate.instant('manager.page.export_error')
        );
      }
    });
  }

  /** CSV uses responseType:'blob', so 413 error bodies also arrive as a Blob, not parsed JSON -- the JSON-endpoint 413 path above doesn't apply here. */
  private handleCsvError(err: HttpErrorResponse): void {
    if (err.status === 413 && err.error instanceof Blob) {
      err.error.text().then((text) => {
        try {
          const parsed = JSON.parse(text);
          this.csvError.set(parsed.detail ?? this.translate.instant('manager.page.export_error'));
        } catch {
          this.csvError.set(this.translate.instant('manager.page.export_error'));
        }
      });
      return;
    }
    this.csvError.set(this.translate.instant('manager.page.export_error'));
  }

  private downloadBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    link.click();
    URL.revokeObjectURL(url);
  }
}
