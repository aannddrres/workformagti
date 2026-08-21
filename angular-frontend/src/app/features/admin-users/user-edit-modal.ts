import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { AdminUsersService } from '../../core/services/admin-users.service';
import { AdminUser, PermissionDeltaState, PermissionOverrideDelta, PermissionOverrideState } from '../../core/models/admin-user';
import { defaultPermissionsForRole, PERMISSION_GROUPS } from '../../shared/permission-catalog';
import { DEPARTMENTS, ROLES } from '../../shared/user-roles';

interface PermissionOptionState {
  value: string;
  label: string;
  effective: boolean;
  inherited: boolean;
  overrideState: PermissionDeltaState;
  disabled: boolean;
  lockReason: 'none' | 'admin-bypass';
}

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
  protected readonly editOverrides = signal<Map<string, PermissionOverrideState>>(new Map());
  private originalOverrides = new Map<string, PermissionOverrideState>();
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  /**
   * True when the user being edited holds every permission unconditionally,
   * regardless of what is stored. See PermissionLockReason.
   */
  protected readonly bypassesPermissionChecks = computed(() => this.editRole() === 'admin');

  protected readonly editPermissionGroups = computed<PermissionGroupState[]>(() => {
    const role = this.editRole();
    const overrides = this.editOverrides();
    const defaults = new Set(defaultPermissionsForRole(role));
    const bypass = this.bypassesPermissionChecks();
    return PERMISSION_GROUPS.map((group) => ({
      heading: group.heading,
      options: group.options.map((option) => {
        const inherited = defaults.has(option.value);
        const overrideState: PermissionDeltaState = overrides.get(option.value) ?? 'INHERIT';
        return {
          value: option.value,
          label: option.label,
          inherited,
          overrideState,
          disabled: bypass,
          effective: bypass || overrideState === 'ALLOW' || (overrideState === 'INHERIT' && inherited),
          lockReason: bypass ? 'admin-bypass' : 'none'
        };
      })
    }));
  });
  protected readonly effectivePermissionCount = computed(() =>
    this.editPermissionGroups().flatMap((group) => group.options).filter((option) => option.effective).length
  );

  constructor() {
    effect(() => {
      const u = this.user();
      this.editRole.set(u.role);
      this.editDepartment.set(u.department || 'All');
      this.editPosition.set(u.position || '');
      const overrides = new Map(
        (u.permission_overrides ?? []).map((override) => [override.permission, override.state] as const)
      );
      this.originalOverrides = new Map(overrides);
      this.editOverrides.set(overrides);
      this.saveError.set(null);
    });
  }

  close(): void {
    this.closed.emit();
  }

  onEditRoleChange(event: Event): void {
    this.editRole.set((event.target as HTMLSelectElement).value);
  }

  setPermissionOverride(value: string, state: PermissionDeltaState): void {
    const next = new Map(this.editOverrides());
    if (state === 'INHERIT') next.delete(value);
    else next.set(value, state);
    this.editOverrides.set(next);
  }

  submitEdit(event: Event): void {
    event.preventDefault();
    const user = this.user();
    this.saving.set(true);
    this.saveError.set(null);

    const deltas: PermissionOverrideDelta[] = this.editPermissionGroups()
      .flatMap((group) => group.options)
      .map((option) => ({
        permission: option.value,
        state: this.editOverrides().get(option.value) ?? 'INHERIT'
      } as PermissionOverrideDelta))
      .filter((delta) => delta.state !== (this.originalOverrides.get(delta.permission) ?? 'INHERIT'));

    this.usersService
      .update(user.id, {
        role: this.editRole(),
        department: this.editDepartment() === 'All' ? null : this.editDepartment(),
        position: this.editPosition() || null
      })
      .subscribe({
        next: (updatedUser) => {
          if (deltas.length === 0) {
            this.saving.set(false);
            this.saved.emit();
            return;
          }
          this.usersService.updatePermissions(user.id, {
            lock_version: updatedUser.lock_version,
            overrides: deltas
          }).subscribe({
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
