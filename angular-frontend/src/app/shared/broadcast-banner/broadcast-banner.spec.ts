import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { BroadcastService } from '../../core/services/broadcast.service';
import { BroadcastBanner } from './broadcast-banner';

describe('BroadcastBanner', () => {
  it('renders every active announcement passively with no dismiss control', async () => {
    TestBed.configureTestingModule({
      providers: [{
        provide: BroadcastService,
        useValue: { active: () => of([{
          id: 1,
          message: 'ოფისი დროებით დაკეტილია',
          priority: 'CRITICAL',
          published_at: '2026-08-22T12:00:00+04:00',
          ends_at: '2026-08-22T18:00:00+04:00',
          ended_at: null,
          publisher_name: 'ადმინისტრატორი',
          ended_by_name: null,
          status: 'active',
          can_end_early: false,
          lock_version: 0
        }]) }
      }]
    });
    const fixture = TestBed.createComponent(BroadcastBanner);
    await fixture.whenStable();
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.textContent).toContain('ოფისი დროებით დაკეტილია');
    expect(root.textContent).toContain('კრიტიკული');
    expect(root.querySelector('button')).toBeNull();
    expect(root.querySelector('[role="dialog"]')).toBeNull();
  });
});
