import { Component, computed, inject, input, signal } from '@angular/core';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { AdminUsersService } from '../../core/services/admin-users.service';
import { AuthService } from '../../core/auth/auth.service';
import { AdminUser } from '../../core/models/admin-user';
import { UserEditModal } from '../admin-users/user-edit-modal';

const ROLE_ORDER = ['admin', 'content_admin', 'manager', 'operator'];
const ROLE_ICONS: Record<string, string> = {
  admin: 'fa-shield-halved',
  content_admin: 'fa-pen-nib',
  manager: 'fa-users-gear',
  operator: 'fa-headset'
};

/**
 * Port of #admin-roles (base-layout.html:2194-2252) + renderRoleConsole/
 * renderRoleConsoleCards/renderRoleConsoleMembers/toggleRoleMember/
 * toggleAllRoleMembers/bulkReassignRole (static/frontend_api.js:1836-1992).
 * Full rewrite against signals -- pick a role card, see its members, bulk-move
 * a selection to another role, or edit one person via the same
 * {@link UserEditModal} the Users management page uses (Python shares one
 * `openUserEditModal` between both screens too).
 *
 * <p>Deliberate deviation: Python's `showConfirm` before a bulk move is a
 * custom-styled modal this workspace has no equivalent of yet; a native
 * `window.confirm()` is used instead -- same functional gate (an explicit
 * confirmation step before a multi-user role change goes through), just
 * plainer chrome. Not worth building a whole confirm-dialog component for
 * this one call site.
 */
@Component({
  selector: 'app-admin-roles-page',
  standalone: true,
  imports: [TranslatePipe, UserEditModal],
  templateUrl: './admin-roles-page.html'
})
export class AdminRolesPage {
  readonly embedded = input(false);
  private readonly usersService = inject(AdminUsersService);
  private readonly authService = inject(AuthService);
  private readonly translate = inject(TranslateService);

  protected readonly roleOrder = ROLE_ORDER;
  protected readonly roleIcons = ROLE_ICONS;

  protected readonly users = signal<AdminUser[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal(false);

  protected readonly activeRole = signal('operator');
  protected readonly selection = signal<Set<number>>(new Set());
  protected readonly targetRole = signal('operator');
  protected readonly reassigning = signal(false);
  protected readonly reassignError = signal<string | null>(null);

  protected readonly selectedUser = signal<AdminUser | null>(null);

  private readonly currentUserEmail = computed(() => this.authService.currentUser()?.email ?? null);

  protected readonly roleCounts = computed<Record<string, number>>(() => {
    const counts: Record<string, number> = { admin: 0, content_admin: 0, manager: 0, operator: 0 };
    for (const u of this.users()) {
      if (counts[u.role] !== undefined) counts[u.role]++;
    }
    return counts;
  });

  protected readonly members = computed(() => this.users().filter((u) => u.role === this.activeRole()));

  protected readonly selectedCount = computed(() => this.selection().size);

  protected readonly allSelectableSelected = computed(() => {
    const selectable = this.members().filter((u) => u.email !== this.currentUserEmail());
    return selectable.length > 0 && selectable.every((u) => this.selection().has(u.id));
  });

  constructor() {
    this.loadUsers();
  }

  refresh(): void {
    this.loadUsers();
  }

  private loadUsers(): void {
    this.loading.set(true);
    this.error.set(false);
    this.usersService.list().subscribe({
      next: (data) => {
        this.users.set(data);
        this.loading.set(false);
        this.syncTargetRole();
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      }
    });
  }

  private syncTargetRole(): void {
    if (this.targetRole() === this.activeRole()) {
      const alt = ['operator', 'manager', 'content_admin', 'admin'].find((r) => r !== this.activeRole());
      if (alt) this.targetRole.set(alt);
    }
  }

  isSelf(user: AdminUser): boolean {
    return this.currentUserEmail() === user.email;
  }

  cardClass(role: string): string {
    return role === this.activeRole()
      ? 'flex items-center gap-3 rounded-2xl border p-4 text-left shadow-sm transition-colors border-brand bg-red-50 ring-1 ring-brand dark:bg-red-950/20'
      : 'flex items-center gap-3 rounded-2xl border p-4 text-left shadow-sm transition-colors border-gray-200 bg-white hover:bg-gray-50 dark:border-zinc-800 dark:bg-zinc-900 dark:hover:bg-zinc-800';
  }

  cardIconClass(role: string): string {
    return role === this.activeRole()
      ? 'flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-brand dark:bg-brand-700 text-white'
      : 'flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-gray-100 text-gray-600 dark:bg-zinc-800 dark:text-zinc-400';
  }

  selectRole(role: string): void {
    this.activeRole.set(role);
    this.selection.set(new Set());
    this.reassignError.set(null);
    this.syncTargetRole();
  }

  toggleMember(id: number, checked: boolean): void {
    const next = new Set(this.selection());
    if (checked) next.add(id);
    else next.delete(id);
    this.selection.set(next);
  }

  toggleAll(checked: boolean): void {
    const next = new Set(this.selection());
    for (const u of this.members()) {
      if (u.email === this.currentUserEmail()) continue;
      if (checked) next.add(u.id);
      else next.delete(u.id);
    }
    this.selection.set(next);
  }

  onTargetRoleChange(event: Event): void {
    this.targetRole.set((event.target as HTMLSelectElement).value);
  }

  submitBulkReassign(): void {
    const ids = [...this.selection()];
    if (!ids.length) {
      this.reassignError.set(this.translate.instant('roles.no_selection'));
      return;
    }
    if (!window.confirm(this.translate.instant('roles.confirm_message'))) {
      return;
    }
    this.reassigning.set(true);
    this.reassignError.set(null);
    this.usersService.bulkReassignRole(ids, this.targetRole()).subscribe({
      next: () => {
        this.reassigning.set(false);
        this.selection.set(new Set());
        this.loadUsers();
      },
      error: (err) => {
        this.reassigning.set(false);
        this.reassignError.set(err?.error?.detail ?? null);
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
