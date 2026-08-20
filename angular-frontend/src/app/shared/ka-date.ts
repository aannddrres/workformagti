/** Matches `new Date(x).toLocaleDateString('ka-GE', {day:'2-digit', month:'2-digit', year:'numeric'})`
 *  used throughout the original frontend -- native Intl, no Angular locale
 *  registration needed. */
export function formatKaDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ka-GE', { day: '2-digit', month: '2-digit', year: 'numeric' });
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
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${pad(value.getDate())} ${KA_SHORT_MONTHS[value.getMonth()]} ${value.getFullYear()}, ${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`;
}
