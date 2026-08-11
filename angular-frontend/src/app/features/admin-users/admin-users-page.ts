import { Component, computed, inject, signal } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { AdminUsersService } from '../../core/services/admin-users.service';
import { AuthService } from '../../core/auth/auth.service';
import { AdminUser, GroupLeader } from '../../core/models/admin-user';
import { defaultPermissionsForRole, PERMISSION_GROUPS } from '../../shared/permission-catalog';

const DEPARTMENTS = ['All', 'ტექნიკური', 'საინფო', 'ოფისი'];
const ROLES = ['operator', 'manager', 'content_admin', 'admin'];

interface PermissionOptionState {
  value: string;
  label: string;
  checked: boolean;
  disabled: boolean;
}

interface PermissionGroupState {
  heading: string;
  options: PermissionOptionState[];
}

/**
 * Port of #admin-users (base-layout.html:2094-2186) + its lazy-load-on-login
 * data functions (fetchAndRenderUsers/loadGroupLeaders/renderUsersAdminTable,
 * static/frontend_api.js) + the edit-user modal (base-layout.html:917-1040).
 * Full rewrite against signals, not a line translation.
 *
 * <p>Real bug found and fixed here, with the user's explicit sign-off: the
 * Python edit-user modal's "Granular Permissions" checkboxes use
 * colon-named values (`content:editor`, `reports:view_global`, ...) that
 * have never matched the backend's actual whitelist
 * (routers/users.py:460-465, dotted: `articles.edit`, `reports.export`,
 * ...) -- every save from that screen has always 400'd in the live app.
 * Built here against the real, working 9-value dotted catalog
 * ({@link PERMISSION_GROUPS}, ported from Permission.java) instead of
 * porting the broken checkbox values.
 *
 * <p>Role console (bulk role reassignment, /admin/roles) is a separate
 * Python sub-page and a separate future slice -- not built here.
 */
@Component({
  selector: 'app-admin-users-page',
  standalone: true,
  imports: [TranslatePipe],
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
  protected readonly editRole = signal('operator');
  protected readonly editDepartment = signal('All');
  protected readonly editPosition = signal('');
  protected readonly editPermissions = signal<Set<string>>(new Set());
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly editPermissionGroups = computed<PermissionGroupState[]>(() => {
    const role = this.editRole();
    const userPerms = this.editPermissions();
    const defaults = new Set(defaultPermissionsForRole(role));
    return PERMISSION_GROUPS.map((group) => ({
      heading: group.heading,
      options: group.options.map((option) => ({
        value: option.value,
        label: option.label,
        disabled: defaults.has(option.value),
        checked: defaults.has(option.value) || userPerms.has(option.value)
      }))
    }));
  });

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
    this.editRole.set(user.role);
    this.editDepartment.set(user.department || 'All');
    this.editPosition.set(user.position || '');
    this.editPermissions.set(new Set(user.permissions));
    this.saveError.set(null);
  }

  closeEditModal(): void {
    this.selectedUser.set(null);
  }

  onEditRoleChange(event: Event): void {
    this.editRole.set((event.target as HTMLSelectElement).value);
    this.editPermissions.set(new Set());
  }

  togglePermission(value: string, checked: boolean): void {
    const next = new Set(this.editPermissions());
    if (checked) next.add(value);
    else next.delete(value);
    this.editPermissions.set(next);
  }

  submitEdit(event: Event): void {
    event.preventDefault();
    const user = this.selectedUser();
    if (!user) return;
    this.saving.set(true);
    this.saveError.set(null);

    const permissions = this.editPermissionGroups()
      .flatMap((group) => group.options)
      .filter((option) => option.checked)
      .map((option) => option.value);

    this.usersService
      .update(user.id, {
        role: this.editRole(),
        department: this.editDepartment() === 'All' ? null : this.editDepartment(),
        position: this.editPosition() || null
      })
      .subscribe({
        next: () => {
          this.usersService.updatePermissions(user.id, permissions).subscribe({
            next: () => {
              this.saving.set(false);
              this.closeEditModal();
              this.loadUsers();
            },
            error: (err) => {
              this.saving.set(false);
              this.saveError.set(err?.error?.detail ?? null);
            }
          });
        },
        error: (err) => {
          this.saving.set(false);
          this.saveError.set(err?.error?.detail ?? null);
        }
      });
  }
}
