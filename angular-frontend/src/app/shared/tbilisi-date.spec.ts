import { tbilisiIsoDate, tbilisiIsoDateDaysBefore } from './tbilisi-date';

describe('tbilisiIsoDate', () => {
  it('is already the next day from 20:00 UTC, when it is midnight in Tbilisi', () => {
    expect(tbilisiIsoDate(new Date('2026-09-25T19:59:59Z'))).toBe('2026-09-25');
    expect(tbilisiIsoDate(new Date('2026-09-25T20:00:00Z'))).toBe('2026-09-26');
  });

  it('is the day a Tbilisi clock shows at 01:30, where the UTC date is still the day before', () => {
    expect(tbilisiIsoDate(new Date('2026-09-26T01:30:00+04:00'))).toBe('2026-09-26');
    expect(new Date('2026-09-26T01:30:00+04:00').toISOString().slice(0, 10)).toBe('2026-09-25');
  });

  it('counts whole days back on the Tbilisi calendar', () => {
    const now = new Date('2026-09-26T01:30:00+04:00');
    expect(tbilisiIsoDateDaysBefore(now, 0)).toBe('2026-09-26');
    expect(tbilisiIsoDateDaysBefore(now, 7)).toBe('2026-09-19');
    expect(tbilisiIsoDateDaysBefore(now, 30)).toBe('2026-08-27');
  });
});
