/** Matches `new Date(x).toLocaleDateString('ka-GE', {day:'2-digit', month:'2-digit', year:'numeric'})`
 *  used throughout the original frontend -- native Intl, no Angular locale
 *  registration needed. */
export function formatKaDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ka-GE', { day: '2-digit', month: '2-digit', year: 'numeric' });
}

/** Matches `new Date(x).toLocaleString('ka-GE')` (audit-dashboard.js) -- date + time, default Intl options. */
export function formatKaDateTime(iso: string): string {
  return new Date(iso).toLocaleString('ka-GE');
}
