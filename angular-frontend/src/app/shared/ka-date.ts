const pad2 = (part: number): string => String(part).padStart(2, '0');

const KA_SHORT_MONTHS = ['იან.', 'თებ.', 'მარ.', 'აპრ.', 'მაი.', 'ივნ.', 'ივლ.', 'აგვ.', 'სექ.', 'ოქტ.', 'ნოე.', 'დეკ.'];

/** The calendar's heading (shared/date-field). */
export const KA_MONTHS = [
  'იანვარი', 'თებერვალი', 'მარტი', 'აპრილი', 'მაისი', 'ივნისი',
  'ივლისი', 'აგვისტო', 'სექტემბერი', 'ოქტომბერი', 'ნოემბერი', 'დეკემბერი'
];

/** Monday first, as the Georgian week runs. */
export const KA_WEEKDAYS = [
  { short: 'ორშ', full: 'ორშაბათი' },
  { short: 'სამ', full: 'სამშაბათი' },
  { short: 'ოთხ', full: 'ოთხშაბათი' },
  { short: 'ხუთ', full: 'ხუთშაბათი' },
  { short: 'პარ', full: 'პარასკევი' },
  { short: 'შაბ', full: 'შაბათი' },
  { short: 'კვი', full: 'კვირა' }
];

/**
 * Every date the portal shows goes through this file, in one of four shapes
 * (owner decision კ4, 2026-09-29):
 *
 *   formatKaDate            29 სექ. 2026
 *   formatKaDateTime        29 სექ. 2026, 17:45
 *   formatKaDateTimeSeconds 29 სექ. 2026, 17:45:08   -- the audit trail only
 *   formatKaDayMonth        29 სექ.                  -- chart axes
 *
 * Before that the same day was `29.09.2026`, `2026-09-29` and
 * `29 სექ. 2026, 17:45:08` depending on the screen, and seconds appeared on a
 * reminder or a login time where nobody reads them.
 *
 * None of this delegates to `toLocaleDateString('ka-GE', ...)`, as the first
 * version did until 2026-09-08. Chromium ships no `ka` CLDR data in several
 * builds -- `Intl.DateTimeFormat.supportedLocalesOf(['ka-GE', 'ka'])` returns
 * `[]` while `de-DE` resolves -- so the call silently fell back to the default
 * locale and rendered the US month-first `08/19/2026`. On this portal that
 * number is a mandatory-reading deadline, so the ambiguity is not cosmetic.
 */
export function formatKaDate(value: string): string {
  const date = parse(value);
  if (!date) return '—';
  return `${date.getDate()} ${KA_SHORT_MONTHS[date.getMonth()]} ${date.getFullYear()}`;
}

export function formatKaDateTime(value: string): string {
  const date = parse(value);
  if (!date) return '—';
  return `${formatKaDate(value)}, ${pad2(date.getHours())}:${pad2(date.getMinutes())}`;
}

/** Seconds matter only where events are ordered against each other. */
export function formatKaDateTimeSeconds(value: string): string {
  const date = parse(value);
  if (!date) return '—';
  return `${formatKaDateTime(value)}:${pad2(date.getSeconds())}`;
}

export function formatKaDayMonth(value: string): string {
  const date = parse(value);
  if (!date) return '—';
  return `${date.getDate()} ${KA_SHORT_MONTHS[date.getMonth()]}`;
}

/**
 * A bare `2026-09-29` -- a due date, a chart bucket -- is a calendar day, but
 * `new Date('2026-09-29')` reads it as UTC midnight, which is the previous
 * evening anywhere west of Greenwich. It is built from its parts instead.
 */
function parse(value: string | null | undefined): Date | null {
  if (!value) return null;
  const day = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  const date = day ? new Date(Number(day[1]), Number(day[2]) - 1, Number(day[3])) : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * A mandatory-reading deadline picked as a calendar day, as the instant it
 * ends: the last second of that day in Tbilisi.
 *
 * The drawers used to send `new Date('2026-10-05').toISOString()` -- the trap
 * `parse` above avoids, on the way out. That is UTC midnight, stored as
 * 04:00 on the 5th, and the server calls a reading overdue once its deadline
 * has passed: everyone was overdue from four in the morning of the day they
 * had been given, and a deadline of "today" was overdue within hours.
 *
 * The offset is written out rather than taken from the browser, so the
 * deadline is the same whichever machine sets it. Georgia keeps +04:00 all
 * year (TbilisiTime on the server).
 */
export function tbilisiEndOfDay(day: string): string {
  return `${day}T23:59:59+04:00`;
}
