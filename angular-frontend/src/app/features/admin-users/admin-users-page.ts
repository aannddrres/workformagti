import { Component, computed, inject, signal } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { AdminUsersService } from '../../core/services/admin-users.service';
import { AuthService } from '../../core/auth/auth.service';
import { AdminUser, GroupLeader } from '../../core/models/admin-user';
import { DEPARTMENTS, ROLES } from '../../shared/user-roles';
import { UserEditModal } from './user-edit-modal';

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
  private readonly usersService = inject(AdminUsersService);
  private readonly authService = inject(AuthService);

  protected readonly currentUserEmail = computed(() => this.authService.currentUser()?.email ?? null);

  protected readonly departments = DEPARTMENTS;
  protected readonly roles = ROLES;

  protected readonly users = signal<AdminUser[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal(false);

  protected readonly groupLeaders = signal<GroupLeader[]>([]);
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

  private loadGroupLeaders(): void {
    this.usersService.groupLeaders().subscribe({
      next: (data) => this.groupLeaders.set(data),
      error: () => {}
    });
  }

  onGroupFilterChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.selectedManagerId.set(value ? Number(value) : null);
    this.loadUsers();
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

  toggleStatus(user: AdminUser): void {
    const next = !user.is_active;
    this.usersService.updateStatus(user.id, next).subscribe({
      next: () => this.loadUsers(),
      error: () => {}
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
