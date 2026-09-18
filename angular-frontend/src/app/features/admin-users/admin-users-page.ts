import { Component, computed, inject, input, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { AdminUsersService } from '../../core/services/admin-users.service';
import { AuthService } from '../../core/auth/auth.service';
import { AdminUser, GroupLeader } from '../../core/models/admin-user';
import { ROLES } from '../../shared/user-roles';
import { UserEditModal } from './user-edit-modal';
import { ToastService } from '../../core/notifications/toast.service';
import { ConfirmService } from '../../core/notifications/confirm.service';
import { StatsService } from '../../core/services/stats.service';
import { formatKaDateTime } from '../../shared/ka-date';
import { formatDepartmentLabel } from '../../shared/department-badge';
import { createTableSort } from '../../shared/table-sort';

/**
 * Port of #admin-users (base-layout.html:2094-2186) + its lazy-load-on-login
 * data functions (fetchAndRenderUsers/loadGroupLeaders/renderUsersAdminTable,
 * static/frontend_api.js). Full rewrite against signals, not a line
 * translation. The edit-user modal itself ({@link UserEditModal}) is a
 * separate, shared component -- reused as-is by the Roles console
 * (/admin/roles), the same way Python's `openUserEditModal` is shared
 * between its RBAC table and its role console.
 *
 * <p>Real bug found and fixed here, with the user's explicit sign-off: the
 * Python edit-user modal's "Granular Permissions" checkboxes use
 * colon-named values (`content:editor`, `reports:view_global`, ...) that
 * have never matched the backend's actual whitelist
 * (routers/users.py:460-465, dotted: `articles.edit`, `reports.export`,
 * ...) -- every save from that screen has always 400'd in the live app.
 * Built here (in {@link UserEditModal}) against the real, working 9-value
 * dotted catalog (`shared/permission-catalog.ts`, ported from
 * Permission.java) instead of porting the broken checkbox values.
 */
@Component({
  selector: 'app-admin-users-page',
  standalone: true,
  imports: [TranslatePipe, UserEditModal],
  templateUrl: './admin-users-page.html'
})
export class AdminUsersPage {
  readonly embedded = input(false);
  private readonly toast = inject(ToastService);
  private readonly confirmService = inject(ConfirmService);
  private readonly translate = inject(TranslateService);
  private readonly usersService = inject(AdminUsersService);
  private readonly authService = inject(AuthService);
  private readonly statsService = inject(StatsService);

  protected readonly currentUserEmail = computed(() => this.authService.currentUser()?.email ?? null);

  protected readonly roles = ROLES;
  protected readonly departmentLabel = formatDepartmentLabel;

  protected readonly users = signal<AdminUser[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal(false);
  protected readonly query = signal('');
  protected readonly roleFilter = signal('');
  protected readonly statusFilter = signal('');
  /** PO-24: how long an account has gone unused. '' | '30' | '60' | '90' | 'never'. */
  protected readonly inactivityFilter = signal('');
  /**
   * The accounts ticked for the sweep, by id rather than by row, so a
   * selection survives sorting and paging -- picking fifty leavers across
   * three pages and losing them on the fourth is the version of this feature
   * nobody would use twice.
   */
  protected readonly selectedIds = signal<ReadonlySet<number>>(new Set<number>());
  protected readonly overdueByUser = signal<Map<number, number>>(new Map());
  protected readonly currentPage = signal(1);
  protected readonly sort = createTableSort<AdminUser>({
    name: (user) => user.name,
    department: (user) => user.department,
    role: (user) => user.role,
    status: (user) => (user.is_active ? 0 : 1),
    lastActive: (user) => user.last_active
  });
  protected readonly pageSize = 50;

  protected readonly filteredUsers = computed(() => {
    const query = this.query().trim().toLocaleLowerCase('ka');
    const role = this.roleFilter();
    const status = this.statusFilter();
    return this.users().filter((user) => {
      const matchesQuery = !query || `${user.name} ${user.email} ${user.department ?? ''}`.toLocaleLowerCase('ka').includes(query);
      const matchesRole = !role || user.role === role;
      const matchesStatus = !status || (status === 'active' ? user.is_active : !user.is_active);
      return matchesQuery && matchesRole && matchesStatus && this.matchesInactivity(user);
    });
  });

  /**
   * PO-24. `never` is its own option rather than the oldest end of the day
   * thresholds: an account created last week and not yet used is not a
   * leaver, and folding a missing `last_active` into "90 days and more" would
   * put every new hire into the sweep the admin is about to tick.
   */
  private matchesInactivity(user: AdminUser): boolean {
    const filter = this.inactivityFilter();
    if (!filter) {
      return true;
    }
    if (filter === 'never') {
      return !user.last_active;
    }
    if (!user.last_active) {
      return false;
    }
    const lastActive = new Date(user.last_active).getTime();
    if (Number.isNaN(lastActive)) {
      return false;
    }
    return Date.now() - lastActive > Number(filter) * 24 * 60 * 60 * 1000;
  }

  /**
   * Who a sweep may touch: never the administrator running it (the backend
   * drops them from the set anyway, and offering a tick box that silently
   * does nothing is worse than not offering one), and never an account that
   * is already off.
   */
  protected readonly selectableUsers = computed(() =>
    this.filteredUsers().filter((user) => user.is_active && !this.isSelf(user)));
  protected readonly selectedCount = computed(() => this.selectedIds().size);
  protected readonly allSelectableSelected = computed(() => {
    const selected = this.selectedIds();
    const selectable = this.selectableUsers();
    return selectable.length > 0 && selectable.every((user) => selected.has(user.id));
  });
  /** Sorted before paging: ordering the fifty rows already on screen would look
   *  like ordering the list and would not be. */
  protected readonly sortedUsers = computed(() => this.sort.sort(this.filteredUsers()));
  protected readonly totalPages = computed(() => Math.max(1, Math.ceil(this.filteredUsers().length / this.pageSize)));
  protected readonly pagedUsers = computed(() => {
    const page = Math.min(this.currentPage(), this.totalPages());
    const start = (page - 1) * this.pageSize;
    return this.sortedUsers().slice(start, start + this.pageSize);
  });

  protected readonly groupLeaders = signal<GroupLeader[]>([]);
  protected readonly groupLeadersFailed = signal(false);
  protected readonly selectedManagerId = signal<number | null>(null);

  protected readonly selectedUser = signal<AdminUser | null>(null);

  constructor() {
    this.loadUsers();
    this.loadGroupLeaders();
    this.statsService.criticalOperators().subscribe({
      next: (data) => this.overdueByUser.set(new Map(data.operators.map((operator) => [operator.user_id, operator.overdue_count])))
    });
  }

  private loadUsers(): void {
    this.loading.set(true);
    this.error.set(false);
    this.usersService.list(this.selectedManagerId()).subscribe({
      next: (data) => {
        this.users.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      }
    });
  }

  /**
   * FE-04: a failed load left the group filter showing only "All groups",
   * which reads as "this company has no group leaders" rather than "the
   * request failed" -- and quietly removes the only way to narrow a
   * ~600-row user list.
   */
  protected loadGroupLeaders(): void {
    this.usersService.groupLeaders().subscribe({
      next: (data) => {
        this.groupLeaders.set(data);
        this.groupLeadersFailed.set(false);
      },
      error: () => this.groupLeadersFailed.set(true)
    });
  }

  onGroupFilterChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.selectedManagerId.set(value ? Number(value) : null);
    this.clearSelection();
    this.loadUsers();
  }

  onQueryChange(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
    this.afterFilterChange();
  }

  onRoleFilterChange(event: Event): void {
    this.roleFilter.set((event.target as HTMLSelectElement).value);
    this.afterFilterChange();
  }

  onStatusFilterChange(event: Event): void {
    this.statusFilter.set((event.target as HTMLSelectElement).value);
    this.afterFilterChange();
  }

  onInactivityFilterChange(event: Event): void {
    this.inactivityFilter.set((event.target as HTMLSelectElement).value);
    this.afterFilterChange();
  }

  /**
   * Narrowing the list drops the selection on purpose. Ticks made under one
   * filter are invisible under the next, and "deactivate 40 accounts" where
   * the admin can see six of them is the accident this feature exists to
   * prevent. Paging does not clear it -- that is navigation, not a change of
   * what is being looked at.
   */
  private afterFilterChange(): void {
    this.currentPage.set(1);
    this.clearSelection();
  }

  isSelectable(user: AdminUser): boolean {
    return user.is_active && !this.isSelf(user);
  }

  isSelected(user: AdminUser): boolean {
    return this.selectedIds().has(user.id);
  }

  toggleSelection(user: AdminUser): void {
    if (!this.isSelectable(user)) {
      return;
    }
    const next = new Set(this.selectedIds());
    if (!next.delete(user.id)) {
      next.add(user.id);
    }
    this.selectedIds.set(next);
  }

  /** Every row the current filter shows, not every row on this page: the
   *  monthly sweep is "filter to 90 days, take all of them". */
  toggleSelectAll(): void {
    this.selectedIds.set(
      this.allSelectableSelected() ? new Set<number>() : new Set(this.selectableUsers().map((user) => user.id)));
  }

  clearSelection(): void {
    this.selectedIds.set(new Set<number>());
  }

  /**
   * PO-24. Deactivation is reversible and keeps every record the person left
   * behind, which is why the confirmation says so: an administrator who
   * thinks this deletes people will not use it, and the accounts it is meant
   * for stay active forever.
   */
  async bulkDeactivate(): Promise<void> {
    const ids = [...this.selectedIds()];
    if (!ids.length) {
      return;
    }
    const confirmed = await this.confirmService.ask({
      title: this.translate.instant('users.admin_page.bulk_confirm_title'),
      message: this.translate.instant('users.admin_page.bulk_confirm', { count: ids.length }),
      confirmLabel: this.translate.instant('users.admin_page.bulk_confirm_label'),
      tone: 'danger'
    });
    if (!confirmed) {
      return;
    }
    this.usersService.bulkDeactivate(ids).subscribe({
      next: (result) => {
        this.toast.success(result.deactivated
          ? this.translate.instant('users.admin_page.bulk_done', { count: result.deactivated })
          : this.translate.instant('users.admin_page.bulk_none_changed'));
        this.clearSelection();
        this.loadUsers();
      },
      error: (err: HttpErrorResponse) => {
        this.toast.error(err.error?.detail ?? this.translate.instant('users.admin_page.bulk_error'));
        this.loadUsers();
      }
    });
  }

  setPage(page: number): void {
    this.currentPage.set(Math.max(1, Math.min(page, this.totalPages())));
  }

  isSelf(user: AdminUser): boolean {
    return this.currentUserEmail() === user.email;
  }

  progressPercent(user: AdminUser): number | null {
    if (user.role !== 'operator') return null;
    if (user.progress_percentage !== null && user.progress_percentage !== undefined) {
      return user.progress_percentage;
    }
    const required = user.required_count ?? 0;
    const read = user.read_count ?? 0;
    return required > 0 ? Math.round((read / required) * 100) : 0;
  }

  overdueCount(user: AdminUser): number {
    return this.overdueByUser().get(user.id) ?? 0;
  }

  lastActiveLabel(value: string | null): string {
    return value ? formatKaDateTime(value) : 'ჯერ არ შესულა';
  }

  /**
   * Activating/deactivating is this app's only way to cut off access, and the
   * failure was silent: the backend answers a refusal with a human-readable
   * Georgian `detail` (e.g. refusing self-deactivation) which the UI threw
   * away, leaving the admin to believe the change had taken effect.
   *
   * loadUsers() also runs on the error path, so the row snaps back to the
   * server's truth instead of showing the state the admin intended.
   */
  toggleStatus(user: AdminUser): void {
    const next = !user.is_active;
    this.usersService.updateStatus(user.id, next).subscribe({
      next: () => this.loadUsers(),
      error: (err: HttpErrorResponse) => {
        this.toast.error(err.error?.detail ?? this.translate.instant('users.admin_page.status_error'));
        this.loadUsers();
      }
    });
  }

  openEditModal(user: AdminUser): void {
    this.selectedUser.set(user);
  }

  closeEditModal(): void {
    this.selectedUser.set(null);
  }

  onEditSaved(): void {
    this.closeEditModal();
    this.loadUsers();
  }
}
