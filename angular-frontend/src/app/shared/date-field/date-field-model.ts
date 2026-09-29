import { KA_MONTHS, KA_WEEKDAYS } from '../ka-date';

/**
 * The value side of shared/date-field, kept apart from the component so it can
 * be tested without a DOM. Values travel in the shapes the native inputs used
 * -- `2026-09-29` and `2026-09-29T14:30` -- so no caller's parsing changed.
 */

const pad2 = (part: number): string => String(part).padStart(2, '0');

export function isoDate(date: Date): string {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;
}

/** The day part of a stored value, or null. */
export function datePart(value: string | null | undefined): string | null {
  const match = /^(\d{4}-\d{2}-\d{2})/.exec(value ?? '');
  return match ? match[1] : null;
}

/** The `HH:mm` part of a stored value, or null. */
export function timePart(value: string | null | undefined): string | null {
  const match = /T(\d{2}):(\d{2})/.exec(value ?? '');
  return match ? `${match[1]}:${match[2]}` : null;
}

/** `2026-09-29T14:30` -> `29.09.2026 14:30`, the way it is typed. */
export function displayValue(value: string | null | undefined, withTime: boolean): string {
  const day = datePart(value);
  if (!day) return '';
  const [year, month, date] = day.split('-');
  const text = `${date}.${month}.${year}`;
  const time = timePart(value);
  return withTime && time ? `${text} ${time}` : text;
}

function validDay(year: number, month: number, day: number): boolean {
  if (year < 1900 || year > 2999 || month < 1 || month > 12 || day < 1) return false;
  return day <= new Date(year, month, 0).getDate();
}

/**
 * What someone typed, as a stored value: `''` for an emptied field, null for
 * text that is not a date. Accepts `29.09.2026`, `29/9/2026`, `29-09-2026` and
 * `2026-09-29`, each optionally followed by a 24-hour time.
 */
export function parseTyped(text: string, withTime: boolean, fallbackTime = '09:00'): string | null {
  const trimmed = text.trim();
  if (!trimmed) return '';
  const match =
    /^(\d{1,2})[./-](\d{1,2})[./-](\d{4})(?:[,\s]+(\d{1,2}):(\d{2}))?$/.exec(trimmed) ??
    /^(\d{4})-(\d{2})-(\d{2})(?:[T\s]+(\d{1,2}):(\d{2}))?$/.exec(trimmed);
  if (!match) return null;
  const isoFirst = match[1].length === 4;
  const year = Number(isoFirst ? match[1] : match[3]);
  const month = Number(match[2]);
  const day = Number(isoFirst ? match[3] : match[1]);
  if (!validDay(year, month, day)) return null;
  const date = `${year}-${pad2(month)}-${pad2(day)}`;
  if (!withTime) return date;
  if (match[4] === undefined) return `${date}T${fallbackTime}`;
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  if (hour > 23 || minute > 59) return null;
  return `${date}T${pad2(hour)}:${pad2(minute)}`;
}

export interface CalendarDay {
  iso: string;
  day: number;
  inMonth: boolean;
  disabled: boolean;
  label: string;
}

/** Six Monday-first weeks around a month, so the grid never changes height. */
export function monthGrid(year: number, month: number, min: string | null): CalendarDay[][] {
  const first = new Date(year, month, 1);
  const offset = (first.getDay() + 6) % 7;
  const start = new Date(year, month, 1 - offset);
  const weeks: CalendarDay[][] = [];
  for (let w = 0; w < 6; w++) {
    const week: CalendarDay[] = [];
    for (let d = 0; d < 7; d++) {
      const date = new Date(start.getFullYear(), start.getMonth(), start.getDate() + w * 7 + d);
      const iso = isoDate(date);
      week.push({
        iso,
        day: date.getDate(),
        inMonth: date.getMonth() === month,
        disabled: min !== null && iso < min,
        label: `${date.getDate()} ${KA_MONTHS[date.getMonth()]} ${date.getFullYear()}, ${KA_WEEKDAYS[(date.getDay() + 6) % 7].full}`
      });
    }
    weeks.push(week);
  }
  return weeks;
}

/** A day moved by some number of days or months, clamped to the month's end. */
export function shiftDay(iso: string, days: number, months = 0): string {
  const [year, month, day] = iso.split('-').map(Number);
  if (months) {
    const last = new Date(year, month - 1 + months + 1, 0).getDate();
    return isoDate(new Date(year, month - 1 + months, Math.min(day, last)));
  }
  return isoDate(new Date(year, month - 1, day + days));
}
