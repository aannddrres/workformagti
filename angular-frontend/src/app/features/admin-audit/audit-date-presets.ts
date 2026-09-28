/** Calendar-day filter bounds in the portal's Asia/Tbilisi business timezone. */
export function auditDatePreset(days: number, now = new Date()): { start: string; end: string } {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Tbilisi', year: 'numeric', month: '2-digit', day: '2-digit'
  }).formatToParts(now);
  const number = (type: Intl.DateTimeFormatPartTypes): number =>
    Number(parts.find((part) => part.type === type)?.value);
  const midnight = new Date(Date.UTC(number('year'), number('month') - 1, number('day')));
  const end = midnight.toISOString().slice(0, 10);
  midnight.setUTCDate(midnight.getUTCDate() - days);
  return { start: midnight.toISOString().slice(0, 10), end };
}
