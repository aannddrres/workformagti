import { describe, expect, it } from 'vitest';
import { formatKaDate, formatKaDateTime } from './ka-date';

describe('formatKaDate', () => {
  it('is day-first and does not depend on Georgian ICU data', () => {
    // Chromium builds without `ka` CLDR data resolve 'ka-GE' to en-US, which
    // renders this same day as the month-first '08/19/2026'. The 19 is what
    // makes the two readings distinguishable, so keep this date.
    expect(formatKaDate(new Date(2026, 7, 19).toISOString())).toBe('19.08.2026');
  });

  it('zero-pads a single-digit day and month', () => {
    expect(formatKaDate(new Date(2026, 0, 4).toISOString())).toBe('04.01.2026');
  });

  it('renders an invalid value safely', () => {
    expect(formatKaDate('not-a-date')).toBe('—');
  });
});

describe('formatKaDateTime', () => {
  it('uses a deterministic Georgian month label', () => {
    const localAugust = new Date(2026, 7, 21, 9, 5, 7).toISOString();

    expect(formatKaDateTime(localAugust)).toBe('21 აგვ. 2026, 09:05:07');
  });

  it('renders an invalid value safely', () => {
    expect(formatKaDateTime('not-a-date')).toBe('—');
  });
});
