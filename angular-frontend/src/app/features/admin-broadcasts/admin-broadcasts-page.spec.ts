import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { provideTranslateService } from '@ngx-translate/core';
import { BroadcastService } from '../../core/services/broadcast.service';
import { AdminBroadcastsPage } from './admin-broadcasts-page';

describe('AdminBroadcastsPage', () => {
  it('publishes only the fixed company-wide contract and renders an end action only when allowed', async () => {
    const publish = vi.fn((request) => of({
      id: 2, ...request, published_at: new Date().toISOString(), ended_at: null,
      publisher_name: 'ლიდერი', ended_by_name: null, status: 'active', can_end_early: true, lock_version: 0
    }));
    TestBed.configureTestingModule({
      providers: [provideTranslateService({ lang: 'ka', fallbackLang: 'ka' }), {
        provide: BroadcastService,
        useValue: { history: () => of({ items: [], page: 0, size: 50, total_items: 0, total_pages: 0 }), publish, endEarly: vi.fn() }
      }]
    });
    const fixture = TestBed.createComponent(AdminBroadcastsPage);
    await fixture.whenStable();
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    const message = root.querySelector<HTMLTextAreaElement>('[data-broadcast-message]')!;
    message.value = 'საერთო განცხადება';
    message.dispatchEvent(new Event('input'));
    const endsAt = root.querySelector<HTMLInputElement>('[data-broadcast-ends-at] input')!;
    const future = new Date(Date.now() + 60 * 60_000);
    const local = new Date(future.getTime() - future.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
    const [day, time] = local.split('T');
    endsAt.value = `${day.split('-').reverse().join('.')} ${time}`;
    endsAt.dispatchEvent(new Event('input'));
    endsAt.dispatchEvent(new Event('blur'));
    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(publish).toHaveBeenCalledTimes(1);
    expect(publish.mock.calls[0][0]).toEqual(expect.objectContaining({
      message: 'საერთო განცხადება', priority: 'NORMAL'
    }));
    expect(publish.mock.calls[0][0]).not.toHaveProperty('target_department');
    expect(publish.mock.calls[0][0]).not.toHaveProperty('target_role');
    expect(root.querySelector('[data-end-broadcast]')).not.toBeNull();
  });
});
