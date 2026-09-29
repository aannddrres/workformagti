import { describe, expect, it } from 'vitest';
import { displayValue, monthGrid, parseTyped, shiftDay } from './date-field-model';

describe('date field values', () => {
  it('shows a stored value the way it is typed', () => {
    expect(displayValue('2026-09-29', false)).toBe('29.09.2026');
    expect(displayValue('2026-09-29T14:30', true)).toBe('29.09.2026 14:30');
    expect(displayValue('2026-09-29T14:30', false)).toBe('29.09.2026');
    expect(displayValue('', true)).toBe('');
  });

  it('reads the ways a date gets typed, day first', () => {
    expect(parseTyped('29.09.2026', false)).toBe('2026-09-29');
    expect(parseTyped('9/1/2026', false)).toBe('2026-01-09');
    expect(parseTyped('29-09-2026', false)).toBe('2026-09-29');
    expect(parseTyped('2026-09-29', false)).toBe('2026-09-29');
    expect(parseTyped('29.09.2026 7:05', true)).toBe('2026-09-29T07:05');
    expect(parseTyped('2030-01-15T09:30', true)).toBe('2030-01-15T09:30');
  });

  it('gives a date without a time the time it already had', () => {
    expect(parseTyped('29.09.2026', true, '18:00')).toBe('2026-09-29T18:00');
  });

  it('refuses what is not a real date, and treats an emptied field as empty', () => {
    expect(parseTyped('31.02.2026', false)).toBeNull();
    expect(parseTyped('29.13.2026', false)).toBeNull();
    expect(parseTyped('29.09.2026 24:00', true)).toBeNull();
    expect(parseTyped('ხვალ', false)).toBeNull();
    expect(parseTyped('   ', false)).toBe('');
  });
});

describe('the calendar grid', () => {
  it('starts on Monday and always has six weeks', () => {
    const weeks = monthGrid(2026, 8, null); // September 2026 begins on a Tuesday
    expect(weeks).toHaveLength(6);
    expect(weeks[0][0]).toMatchObject({ iso: '2026-08-31', inMonth: false });
    expect(weeks[0][1]).toMatchObject({ iso: '2026-09-01', inMonth: true, label: '1 სექტემბერი 2026, სამშაბათი' });
  });

  it('disables the days before the earliest allowed one', () => {
    const days = monthGrid(2026, 8, '2026-09-15').flat();
    expect(days.find((day) => day.iso === '2026-09-14')?.disabled).toBe(true);
    expect(days.find((day) => day.iso === '2026-09-15')?.disabled).toBe(false);
  });

  it('moves by days and by months, keeping to the month end', () => {
    expect(shiftDay('2026-09-30', 1)).toBe('2026-10-01');
    expect(shiftDay('2026-01-31', 0, 1)).toBe('2026-02-28');
    expect(shiftDay('2026-03-15', 0, -12)).toBe('2025-03-15');
  });
});
