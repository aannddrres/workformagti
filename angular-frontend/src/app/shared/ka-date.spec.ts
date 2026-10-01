import { describe, expect, it } from 'vitest';
import { formatKaDate, formatKaDateTime, formatKaDateTimeSeconds, formatKaDayMonth, tbilisiEndOfDay } from './ka-date';

describe('formatKaDate', () => {
  it('names the month in Georgian and does not depend on Georgian ICU data', () => {
    // Chromium builds without `ka` CLDR data resolve 'ka-GE' to en-US, which
    // renders this same day as the month-first '08/19/2026' or as 'Aug'.
    expect(formatKaDate(new Date(2026, 7, 19).toISOString())).toBe('19 აგვ. 2026');
  });

  it('does not pad a single-digit day', () => {
    expect(formatKaDate(new Date(2026, 0, 4).toISOString())).toBe('4 იან. 2026');
  });

  it('reads a bare calendar day as that day, not as UTC midnight', () => {
    expect(formatKaDate('2026-09-01')).toBe('1 სექ. 2026');
  });

  it('renders an invalid value safely', () => {
    expect(formatKaDate('not-a-date')).toBe('—');
  });
});

describe('formatKaDateTime', () => {
  it('shows hours and minutes, not seconds', () => {
    const localAugust = new Date(2026, 7, 21, 9, 5, 7).toISOString();

    expect(formatKaDateTime(localAugust)).toBe('21 აგვ. 2026, 09:05');
  });

  it('renders an invalid value safely', () => {
    expect(formatKaDateTime('not-a-date')).toBe('—');
  });
});

describe('formatKaDateTimeSeconds', () => {
  it('keeps the seconds the audit trail orders by', () => {
    expect(formatKaDateTimeSeconds(new Date(2026, 8, 29, 17, 45, 8).toISOString())).toBe('29 სექ. 2026, 17:45:08');
  });
});

describe('formatKaDayMonth', () => {
  it('labels a chart bucket without the year', () => {
    expect(formatKaDayMonth('2026-09-23')).toBe('23 სექ.');
  });

  it('renders an invalid value safely', () => {
    expect(formatKaDayMonth('')).toBe('—');
  });
});

describe('tbilisiEndOfDay', () => {
  it('ends the picked day in Tbilisi rather than starting it in UTC', () => {
    expect(tbilisiEndOfDay('2026-10-05')).toBe('2026-10-05T23:59:59+04:00');
    expect(new Date(tbilisiEndOfDay('2026-10-05')).toISOString()).toBe('2026-10-05T19:59:59.000Z');
  });
});
