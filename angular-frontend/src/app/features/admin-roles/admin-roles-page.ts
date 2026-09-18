import { Component, computed, inject, input, signal } from '@angular/core';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { AdminUsersService } from '../../core/services/admin-users.service';
import { AuthService } from '../../core/auth/auth.service';
import { AdminUser } from '../../core/models/admin-user';
import { UserEditModal } from '../admin-users/user-edit-modal';
import { ConfirmService } from '../../core/notifications/confirm.service';
import { createTableSort } from '../../shared/table-sort';

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
 * <p>The bulk move is gated by a confirmation, as Python's `showConfirm` was.
 * This file used to note that a native `window.confirm()` stood in because the
 * workspace had no styled equivalent, and that building one "for this one call
 * site" was not worth it. That was a fair call at one call site; by the
 * seventeenth it was not, so {@link ConfirmService} now exists and this is one
 * of its callers.
 */
@Component({
  selector: 'app-admin-roles-page',
  standalone: true,
  imports: [TranslatePipe, UserEditModal],
  templateUrl: './admin-roles-page.html'
})
export class AdminRolesPage {
  readonly embedded = input(false);
  private readonly confirmService = inject(ConfirmService);
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
  protected readonly sort = createTableSort<AdminUser>({
    name: (u) => u.name,
    email: (u) => u.email,
    department: (u) => u.department
  });
  protected readonly sortedMembers = computed(() => this.sort.sort(this.members()));

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
      ? 'flex items-center gap-3 rounded-lg border p-4 text-left shadow-e1 transition-colors border-brand-accent bg-red-50 ring-1 ring-brand-accent dark:bg-red-950/20'
      : 'flex items-center gap-3 rounded-lg border p-4 text-left shadow-e1 transition-colors border-slate-200 bg-white hover:bg-slate-50 dark:border-slate-800 dark:bg-slate-900 dark:hover:bg-slate-800';
  }

  cardIconClass(role: string): string {
    return role === this.activeRole()
      ? 'flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-brand dark:bg-brand-700 text-white'
      : 'flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-400';
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

  async submitBulkReassign(): Promise<void> {
    const ids = [...this.selection()];
    if (!ids.length) {
      this.reassignError.set(this.translate.instant('roles.no_selection'));
      return;
    }
    if (!(await this.confirmService.ask(this.translate.instant('roles.confirm_message')))) {
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
