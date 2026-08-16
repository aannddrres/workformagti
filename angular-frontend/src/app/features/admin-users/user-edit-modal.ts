import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { AdminUsersService } from '../../core/services/admin-users.service';
import { AdminUser } from '../../core/models/admin-user';
import { defaultPermissionsForRole, PERMISSION_GROUPS } from '../../shared/permission-catalog';
import { DEPARTMENTS, ROLES } from '../../shared/user-roles';

interface PermissionOptionState {
  value: string;
  label: string;
  checked: boolean;
  disabled: boolean;
  lockReason: PermissionLockReason;
}

/**
 * Why a locked switch is locked. The two are not the same reason, and the
 * UI used to give the first one for both.
 *
 * - `role-default`: the role grants it out of the box. Changing the role's
 *   defaults would change this, and the permission IS consulted at runtime.
 * - `admin-bypass`: PermissionChecker.hasPermission returns true for
 *   SYSTEM_ADMIN before it looks at anything, so the stored set is not read
 *   at all. Nothing that can be typed into this modal will restrict a system
 *   admin -- see audit SEC-06.
 */
type PermissionLockReason = 'none' | 'role-default' | 'admin-bypass';

interface PermissionGroupState {
  heading: string;
  options: PermissionOptionState[];
}

/**
 * Extracted from AdminUsersPage so the Roles console (/admin/roles) can
 * reuse the exact same edit experience Python's role-console shares with
 * its RBAC table (both call the same `openUserEditModal`/`submitUserEditForm`
 * in the original -- base-layout.html:917-1040, static/frontend_api.js:1925).
 *
 * See admin-users-page.ts's own header comment for the two real bugs fixed
 * when this modal was first built (broken colon-named permission values,
 * and the native-<select>-[value]-binding timing bug) -- both fixes live
 * here now, not duplicated.
 */
@Component({
  selector: 'app-user-edit-modal',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './user-edit-modal.html'
})
export class UserEditModal {
  private readonly usersService = inject(AdminUsersService);

  readonly user = input.required<AdminUser>();
  readonly closed = output<void>();
  readonly saved = output<void>();

  protected readonly roles = ROLES;
  protected readonly departments = DEPARTMENTS;

  protected readonly editRole = signal('operator');
  protected readonly editDepartment = signal('All');
  protected readonly editPosition = signal('');
  protected readonly editPermissions = signal<Set<string>>(new Set());
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  /**
   * True when the user being edited holds every permission unconditionally,
   * regardless of what is stored. See PermissionLockReason.
   */
  protected readonly bypassesPermissionChecks = computed(() => this.editRole() === 'admin');

  protected readonly editPermissionGroups = computed<PermissionGroupState[]>(() => {
    const role = this.editRole();
    const userPerms = this.editPermissions();
    const defaults = new Set(defaultPermissionsForRole(role));
    const bypass = this.bypassesPermissionChecks();
    return PERMISSION_GROUPS.map((group) => ({
      heading: group.heading,
      options: group.options.map((option) => ({
        value: option.value,
        label: option.label,
        disabled: bypass || defaults.has(option.value),
        checked: bypass || defaults.has(option.value) || userPerms.has(option.value),
        // A system admin's switches are locked for a different reason than a
        // content admin's, and saying "from role" for both was the
        // inaccuracy: for an admin the permission is never consulted, so
        // changing the role's defaults would not change the outcome.
        lockReason: bypass ? 'admin-bypass' : defaults.has(option.value) ? 'role-default' : 'none'
      }))
    }));
  });

  constructor() {
    effect(() => {
      const u = this.user();
      this.editRole.set(u.role);
      this.editDepartment.set(u.department || 'All');
      this.editPosition.set(u.position || '');
      this.editPermissions.set(new Set(u.permissions));
      this.saveError.set(null);
    });
  }

  close(): void {
    this.closed.emit();
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
    const user = this.user();
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
              this.saved.emit();
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
