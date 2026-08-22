import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { OrgAdminService } from '../../core/services/org-admin.service';
import { AdminBackfillPage } from './admin-backfill-page';

describe('AdminBackfillPage', () => {
  it('shows unexercised before counters and never disables apply because decisions remain', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), {
        provide: OrgAdminService,
        useValue: {
          policyShadow: () => of({
            decisions: { 'scope.export': { unexercised: true, agreed: 0, disagreed: 0 } },
            generated_at: '2026-08-22T12:00:00+04:00'
          }),
          backfillReport: () => of({
            groups_to_create: 1,
            memberships: 2,
            leaders_resolved: 0,
            needs_a_decision: 2,
            blocks_cutover: true,
            issues: [],
            report: 'V37 must NOT be applied',
            generated_at: '2026-08-22T12:00:00+04:00'
          }),
          applyBackfill: () => of({})
        }
      }]
    });

    const fixture = TestBed.createComponent(AdminBackfillPage);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    const unexercised = root.querySelector('[data-shadow-unexercised="true"]');
    const counts = root.querySelector('[data-shadow-counts]');
    const apply = root.querySelector<HTMLButtonElement>('[data-backfill-apply]');

    expect(unexercised).not.toBeNull();
    expect(counts).not.toBeNull();
    expect(unexercised!.compareDocumentPosition(counts!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(apply?.disabled).toBe(false);
    expect(root.textContent).toContain('V37');
  });
});
