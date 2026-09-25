/**
 * Calendar dates as the server reads them.
 *
 * The backend filters `YYYY-MM-DD` values as Tbilisi calendar days
 * (TbilisiTime.OFFSET, a fixed +04:00 -- Georgia has kept no daylight saving
 * since 2005). `Date.toISOString().slice(0, 10)` is the UTC day instead, which
 * is the previous day for the first four hours of every Tbilisi day: an
 * auditor pressing "today" at 01:00 got yesterday, and the rows written since
 * midnight fell outside the range. The offset is applied explicitly, so the
 * answer does not depend on the workstation's own timezone either.
 */
const TBILISI_OFFSET_MS = 4 * 60 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;

/** The Tbilisi calendar date of an instant, `YYYY-MM-DD`. */
export function tbilisiIsoDate(instant: Date): string {
  return new Date(instant.getTime() + TBILISI_OFFSET_MS).toISOString().slice(0, 10);
}

/** The Tbilisi calendar date `days` whole days before an instant. */
export function tbilisiIsoDateDaysBefore(instant: Date, days: number): string {
  return tbilisiIsoDate(new Date(instant.getTime() - days * DAY_MS));
}
