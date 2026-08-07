import { Component, inject, signal } from '@angular/core';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { StatsService } from '../../core/services/stats.service';
import { CriticalOperator, DepartmentDashboard, DepartmentGroupStats, DepartmentStats, GroupMemberCompletion } from '../../core/models/stats';

type SortMode = 'name' | 'compliance';
type Tier = { bar: string; text: string };

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
  imports: [TranslatePipe],
  templateUrl: './team-stats-page.html'
})
export class TeamStatsPage {
  private readonly statsService = inject(StatsService);
  private readonly translate = inject(TranslateService);

  protected readonly dashboard = signal<DepartmentDashboard | null>(null);
  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly sortMode = signal<SortMode>('name');

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

  constructor() {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.statsService.departmentDashboard().subscribe({
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
      return { bar: 'bg-[#EE1D23]', text: 'text-[#EE1D23] dark:text-red-400' };
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
    return 'bg-[#EE1D23]';
  }

  openCriticalModal(): void {
    this.criticalModalOpen.set(true);
    this.criticalLoading.set(true);
    this.criticalError.set(false);
    this.statsService.criticalOperators().subscribe({
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
}
