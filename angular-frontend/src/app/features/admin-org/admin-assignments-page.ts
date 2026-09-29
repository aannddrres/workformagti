import { Component, computed, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { AdminUser } from '../../core/models/admin-user';
import {
  LeadershipAssignment,
  LeadershipAssignmentSource,
  LeadershipAssignmentType,
  LeadershipScope,
  OrgStructure
} from '../../core/models/org-admin';
import { AdminUsersService } from '../../core/services/admin-users.service';
import { OrgAdminService } from '../../core/services/org-admin.service';
import { ConfirmService } from '../../core/notifications/confirm.service';
import { createTableSort } from '../../shared/table-sort';

@Component({
  selector: 'app-admin-assignments-page',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './admin-assignments-page.html'
})
export class AdminAssignmentsPage {
  private readonly confirmService = inject(ConfirmService);
  private readonly orgAdmin = inject(OrgAdminService);
  private readonly adminUsers = inject(AdminUsersService);
  private readonly formBuilder = inject(NonNullableFormBuilder);

  protected readonly structure = signal<OrgStructure | null>(null);
  protected readonly assignments = signal<LeadershipAssignment[]>([]);
  protected readonly sort = createTableSort<LeadershipAssignment>({
    leader: (a) => a.user_name,
    scope: (a) => a.department_name ?? a.team_name,
    type: (a) => a.assignment_type,
    source: (a) => a.source,
    status: (a) => (a.is_active ? 0 : 1)
  });
  protected readonly sortedAssignments = computed(() => this.sort.sort(this.assignments()));

  protected typeLabel(type: LeadershipAssignmentType): string {
    return type === 'ACTING' ? 'მოვალეობის შემსრულებელი' : 'ძირითადი';
  }

  protected sourceLabel(source: LeadershipAssignmentSource): string {
    return { MANUAL: 'ხელით', BACKFILL: 'საწყისი შევსება', AD_SYNC: 'კომპანიის AD' }[source] ?? source;
  }
  protected readonly users = signal<AdminUser[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly selectedScopeType = signal<LeadershipScope>('GROUP');

  protected readonly form = this.formBuilder.group({
    userId: [0, [Validators.required, Validators.min(1)]],
    scopeType: ['GROUP' as LeadershipScope, Validators.required],
    scopeId: [0, [Validators.required, Validators.min(1)]],
    assignmentType: ['PRIMARY' as LeadershipAssignmentType, Validators.required]
  });

  protected readonly activeUsers = computed(() => this.users()
    .filter((user) => user.is_active)
    .sort((left, right) => left.name.localeCompare(right.name)));

  protected readonly scopeOptions = computed(() => {
    const structure = this.structure();
    if (!structure) return [];
    if (this.selectedScopeType() === 'DEPARTMENT') {
      return structure.departments.map((department) => ({ id: department.id, label: department.name }));
    }
    return structure.departments.flatMap((department) => department.teams.map((team) => ({
      id: team.id,
      label: `${department.name} — ${team.name}`
    })));
  });

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    forkJoin({
      structure: this.orgAdmin.structure(),
      assignments: this.orgAdmin.assignments(),
      users: this.adminUsers.list()
    }).subscribe({
      next: ({ structure, assignments, users }) => {
        this.structure.set(structure);
        this.assignments.set(assignments);
        this.users.set(users);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(error?.error?.detail ?? 'დანიშვნების ჩატვირთვა ვერ მოხერხდა.');
        this.loading.set(false);
      }
    });
  }

  protected changeScopeType(event: Event): void {
    const scopeType = (event.target as HTMLSelectElement).value as LeadershipScope;
    this.form.controls.scopeType.setValue(scopeType);
    this.selectedScopeType.set(scopeType);
    this.form.controls.scopeId.setValue(0);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    this.saving.set(true);
    this.error.set(null);
    this.orgAdmin.createAssignment({
      user_id: value.userId,
      department_id: value.scopeType === 'DEPARTMENT' ? value.scopeId : null,
      team_id: value.scopeType === 'GROUP' ? value.scopeId : null,
      assignment_type: value.assignmentType
    }).subscribe({
      next: (created) => {
        this.assignments.update((rows) => [created, ...rows]);
        this.form.controls.scopeId.setValue(0);
        this.saving.set(false);
      },
      error: (error) => {
        this.error.set(error?.error?.detail ?? 'დანიშვნის შენახვა ვერ მოხერხდა.');
        this.saving.set(false);
      }
    });
  }

  protected async deactivate(assignment: LeadershipAssignment): Promise<void> {
    if (!assignment.is_active || !(await this.confirmService.ask({ message: 'ნამდვილად გსურთ ამ დანიშვნის გაუქმება? ისტორია შენარჩუნდება.', confirmLabel: 'დანიშვნის გაუქმება', tone: 'danger' }))) {
      return;
    }
    this.error.set(null);
    this.orgAdmin.deactivateAssignment(assignment.id).subscribe({
      next: (updated) => this.assignments.update((rows) =>
        rows.map((row) => row.id === updated.id ? updated : row)),
      error: (error) => this.error.set(error?.error?.detail ?? 'დანიშვნის გაუქმება ვერ მოხერხდა.')
    });
  }
}
