import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AdminUsersService } from '../../core/services/admin-users.service';
import { OrgAdminService } from '../../core/services/org-admin.service';
import { AdminAssignmentsPage } from './admin-assignments-page';

describe('AdminAssignmentsPage', () => {
  it('creates a team assignment with exactly one scope', async () => {
    const createAssignment = vi.fn(() => of({
      id: 99,
      user_id: 7,
      user_name: 'ლიდერი',
      user_email: 'leader@magti.ge',
      scope: 'GROUP',
      department_id: null,
      department_name: null,
      team_id: 10,
      team_name: 'ჯგუფი 01',
      assignment_type: 'PRIMARY',
      is_active: true,
      started_at: '2026-08-22T12:00:00+04:00',
      ended_at: null,
      created_by: 1,
      source: 'MANUAL'
    }));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: OrgAdminService,
          useValue: {
            structure: () => of({ departments: [{
              id: 1,
              stable_key: 'TECHNICAL',
              name: 'ტექნიკური',
              is_active: true,
              teams: [{ id: 10, stable_key: 'GROUP_01', name: 'ჯგუფი 01', is_active: true, member_count: 3 }]
            }] }),
            assignments: () => of([]),
            createAssignment,
            deactivateAssignment: vi.fn()
          }
        },
        {
          provide: AdminUsersService,
          useValue: { list: () => of([{ id: 7, name: 'ლიდერი', email: 'leader@magti.ge', is_active: true }]) }
        }
      ]
    });

    const fixture = TestBed.createComponent(AdminAssignmentsPage);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    const choose = (selector: string, value: string) => {
      const element = root.querySelector<HTMLSelectElement>(selector)!;
      const option = [...element.options].find((candidate) =>
        candidate.value === value || candidate.value.endsWith(`: ${value}`));
      element.value = option?.value ?? value;
      element.dispatchEvent(new Event('change'));
    };
    choose('[data-assignment-user]', '7');
    choose('[data-assignment-scope-type]', 'GROUP');
    choose('[data-assignment-scope-id]', '10');
    choose('[data-assignment-type]', 'PRIMARY');
    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(createAssignment).toHaveBeenCalledWith({
      user_id: 7,
      department_id: null,
      team_id: 10,
      assignment_type: 'PRIMARY'
    });
  });
});
