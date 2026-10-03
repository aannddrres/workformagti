import { vi } from 'vitest';
import { Subject, of, throwError } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { ReadingConfirm } from './reading-confirm';
import { ComplianceService } from '../../../core/services/compliance.service';
import { MarkReadResult, MyReading } from '../../../core/models/compliance';

function reading(overrides: Partial<MyReading> = {}): MyReading {
  return {
    reading: { id: 7, item_type: 'article', item_id: 42, target_department: 'ტექნიკური',
      due_date: '2026-10-10T23:59:59+04:00', priority: 'high' },
    status: 'unread',
    read_at: null,
    is_overdue: false,
    item_title: 'როუმინგი',
    item_content: null,
    changed_since_read: false,
    ...overrides
  } as MyReading;
}

/**
 * The "I have read this" button at the end of an item -- the one place an
 * operator discharges an obligation (QA round 5: it had no unit test). What
 * matters: it appears only for an item the person owes, a refusal is never
 * shown as success, a quiz gate opens the quiz, and passing it confirms.
 */
describe('ReadingConfirm', () => {
  let myReadings: ReturnType<typeof vi.fn>;
  let markRead: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    myReadings = vi.fn();
    markRead = vi.fn();
    await TestBed.configureTestingModule({
      imports: [ReadingConfirm],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTranslateService({ lang: 'ka', fallbackLang: 'ka' }),
        { provide: ComplianceService, useValue: { myReadings, markRead } }
      ]
    }).compileComponents();
  });

  function create(list: MyReading[] | Error, itemId = 42) {
    myReadings.mockReturnValue(list instanceof Error ? throwError(() => list) : of(list));
    const fixture = TestBed.createComponent(ReadingConfirm);
    fixture.componentRef.setInput('itemType', 'article');
    fixture.componentRef.setInput('itemId', itemId);
    fixture.detectChanges(); // ngOnInit, with the inputs in place
    return fixture.componentInstance as any;
  }

  it('finds the obligation for exactly this item and no other', () => {
    expect(create([reading()]).reading()?.reading.id).toBe(7);
    expect(create([reading()], 43).reading()).toBeNull();
  });

  it('says the lookup failed rather than silently showing nothing', () => {
    const component = create(new Error('network'));
    expect(component.reading()).toBeNull();
    expect(component.lookupFailed()).toBe(true);
  });

  it('marks the item read when the server accepts', () => {
    const component = create([reading({ is_overdue: true })]);
    markRead.mockReturnValue(of({ ok: true, quizRequired: false,
      status: { read_at: '2026-10-03T12:00:00+04:00' } } as unknown as MarkReadResult));

    component.confirm();

    expect(markRead).toHaveBeenCalledWith(7);
    expect(component.isRead()).toBe(true);
    expect(component.isOverdue()).toBe(false);
  });

  it('opens the quiz when the server says one must be passed first, then confirms after it', () => {
    const component = create([reading()]);
    markRead.mockReturnValueOnce(of({ ok: false, quizRequired: true } as MarkReadResult));

    component.confirm();
    expect(component.quizOpen()).toBe(true);
    expect(component.isRead()).toBe(false);

    markRead.mockReturnValueOnce(of({ ok: true, quizRequired: false,
      status: { read_at: '2026-10-03T12:05:00+04:00' } } as unknown as MarkReadResult));
    component.onQuizPassed();
    expect(component.quizOpen()).toBe(false);
    expect(markRead).toHaveBeenCalledTimes(2);
    expect(component.isRead()).toBe(true);
  });

  it('never shows a refused confirmation as done', () => {
    const component = create([reading()]);
    markRead.mockReturnValue(of({ ok: false, quizRequired: false } as MarkReadResult));

    component.confirm();

    expect(component.failed()).toBe(true);
    expect(component.isRead()).toBe(false);
  });

  it('ignores a second click while the first is still on its way', () => {
    const component = create([reading()]);
    const pending = new Subject<MarkReadResult>();
    markRead.mockReturnValue(pending);

    component.confirm();
    component.confirm();

    expect(markRead).toHaveBeenCalledTimes(1);
  });

  it('asks again for an item changed since it was confirmed', () => {
    const component = create([reading({ status: 'read', read_at: '2026-10-01T10:00:00+04:00', changed_since_read: true })]);
    expect(component.isRead()).toBe(false);
    expect(component.isChanged()).toBe(true);
  });
});
