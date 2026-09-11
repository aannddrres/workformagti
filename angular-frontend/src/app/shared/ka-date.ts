const pad2 = (part: number): string => String(part).padStart(2, '0');

/**
 * Deterministic Georgian date, `DD.MM.YYYY`.
 *
 * This delegated to `toLocaleDateString('ka-GE', ...)` until 2026-09-08, which
 * is not safe: Chromium ships no `ka` CLDR data in several builds --
 * `Intl.DateTimeFormat.supportedLocalesOf(['ka-GE', 'ka'])` returns `[]` while
 * `de-DE` / `ru-RU` / `az-AZ` resolve -- so the call silently fell back to the
 * default locale and rendered the US month-first `08/19/2026` instead of
 * `19.08.2026`. On this portal that number is a mandatory-reading deadline, so
 * the ambiguity is not cosmetic. `formatKaDateTime` below already avoided Intl
 * for exactly this reason; the two now agree.
 */
export function formatKaDate(iso: string): string {
  const value = new Date(iso);
  if (Number.isNaN(value.getTime())) return '—';
  return `${pad2(value.getDate())}.${pad2(value.getMonth() + 1)}.${value.getFullYear()}`;
}

const KA_SHORT_MONTHS = ['იან.', 'თებ.', 'მარ.', 'აპრ.', 'მაი.', 'ივნ.', 'ივლ.', 'აგვ.', 'სექ.', 'ოქტ.', 'ნოე.', 'დეკ.'];

/**
 * Deterministic Georgian date/time. Chromium builds without full Georgian
 * ICU data can silently fall back to English month names (`Aug`), so this
 * must not rely on locale availability in the workstation image.
 */
export function formatKaDateTime(iso: string): string {
  const value = new Date(iso);
  if (Number.isNaN(value.getTime())) return '—';
  return `${pad2(value.getDate())} ${KA_SHORT_MONTHS[value.getMonth()]} ${value.getFullYear()}, ${pad2(value.getHours())}:${pad2(value.getMinutes())}:${pad2(value.getSeconds())}`;
}
