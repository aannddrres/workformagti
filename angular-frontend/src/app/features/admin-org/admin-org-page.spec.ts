import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { OrgAdminService } from '../../core/services/org-admin.service';
import { AdminOrgPage } from './admin-org-page';

describe('AdminOrgPage', () => {
  it('states AD ownership and exposes no org mutation controls', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: OrgAdminService,
          useValue: {
            structure: () => of({
              departments: [{
                id: 1,
                stable_key: 'TECHNICAL',
                name: 'ტექნიკური',
                is_active: true,
                teams: [{
                  id: 10,
                  stable_key: 'GROUP_01',
                  name: 'ჯგუფი 01',
                  is_active: true,
                  member_count: 3
                }]
              }]
            })
          }
        }
      ]
    });

    const fixture = TestBed.createComponent(AdminOrgPage);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.textContent).toContain('AD');
    expect(root.querySelector('[data-org-mutation]')).toBeNull();
    expect(root.textContent).toContain('ჯგუფი 01');
    expect(root.textContent).toContain('3');
  });
});
