import { Component, computed, inject, input, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { AdminUsersService } from '../../core/services/admin-users.service';
import { AuthService } from '../../core/auth/auth.service';
import { AdminUser, GroupLeader } from '../../core/models/admin-user';
import { DEPARTMENTS, ROLES } from '../../shared/user-roles';
import { UserEditModal } from './user-edit-modal';
import { ToastService } from '../../core/notifications/toast.service';
import { StatsService } from '../../core/services/stats.service';
import { formatKaDateTime } from '../../shared/ka-date';
import { formatDepartmentLabel } from '../../shared/department-badge';

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
  private readonly translate = inject(TranslateService);
  private readonly usersService = inject(AdminUsersService);
  private readonly authService = inject(AuthService);
  private readonly statsService = inject(StatsService);

  protected readonly currentUserEmail = computed(() => this.authService.currentUser()?.email ?? null);

  protected readonly departments = DEPARTMENTS;
  protected readonly roles = ROLES;
  protected readonly departmentLabel = formatDepartmentLabel;

  protected readonly users = signal<AdminUser[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal(false);
  protected readonly query = signal('');
  protected readonly roleFilter = signal('');
  protected readonly statusFilter = signal('');
  protected readonly overdueByUser = signal<Map<number, number>>(new Map());
  protected readonly currentPage = signal(1);
  protected readonly pageSize = 50;

  protected readonly filteredUsers = computed(() => {
    const query = this.query().trim().toLocaleLowerCase('ka');
    const role = this.roleFilter();
    const status = this.statusFilter();
    return this.users().filter((user) => {
      const matchesQuery = !query || `${user.name} ${user.email} ${user.department ?? ''}`.toLocaleLowerCase('ka').includes(query);
      const matchesRole = !role || user.role === role;
      const matchesStatus = !status || (status === 'active' ? user.is_active : !user.is_active);
      return matchesQuery && matchesRole && matchesStatus;
    });
  });
  protected readonly totalPages = computed(() => Math.max(1, Math.ceil(this.filteredUsers().length / this.pageSize)));
  protected readonly pagedUsers = computed(() => {
    const page = Math.min(this.currentPage(), this.totalPages());
    const start = (page - 1) * this.pageSize;
    return this.filteredUsers().slice(start, start + this.pageSize);
  });

  protected readonly groupLeaders = signal<GroupLeader[]>([]);
  protected readonly groupLeadersFailed = signal(false);
  protected readonly selectedManagerId = signal<number | null>(null);

  protected readonly showCreatePanel = signal(false);
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly cuName = signal('');
  protected readonly cuEmail = signal('');
  protected readonly cuDepartment = signal('All');
  protected readonly cuPosition = signal('');
  protected readonly cuRole = signal('operator');
  protected readonly cuPassword = signal('');

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
    this.loadUsers();
  }

  onQueryChange(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
    this.currentPage.set(1);
  }

  onRoleFilterChange(event: Event): void {
    this.roleFilter.set((event.target as HTMLSelectElement).value);
    this.currentPage.set(1);
  }

  onStatusFilterChange(event: Event): void {
    this.statusFilter.set((event.target as HTMLSelectElement).value);
    this.currentPage.set(1);
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

  openCreatePanel(): void {
    this.cuName.set('');
    this.cuEmail.set('');
    this.cuDepartment.set('All');
    this.cuPosition.set('');
    this.cuRole.set('operator');
    this.cuPassword.set('');
    this.createError.set(null);
    this.showCreatePanel.set(true);
  }

  closeCreatePanel(): void {
    this.showCreatePanel.set(false);
  }

  submitCreate(event: Event): void {
    event.preventDefault();
    this.creating.set(true);
    this.createError.set(null);
    this.usersService
      .create({
        name: this.cuName(),
        email: this.cuEmail(),
        department: this.cuDepartment(),
        position: this.cuPosition() || null,
        role: this.cuRole(),
        password: this.cuPassword()
      })
      .subscribe({
        next: () => {
          this.creating.set(false);
          this.closeCreatePanel();
          this.loadUsers();
        },
        error: (err) => {
          this.creating.set(false);
          this.createError.set(err?.error?.detail ?? null);
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
