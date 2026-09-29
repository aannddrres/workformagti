/**
 * The letters on a person's avatar: the first letter of each of their first
 * two words, so "ტექნიკური ოპერატორი" reads "ტო" in the header, the sidebar
 * and the profile page alike. The profile page once took the first two
 * letters of the name instead ("ტე"), so the same person wore two avatars.
 *
 * Only Latin letters are capitalised. `toUpperCase()` turns Georgian into
 * Mtavruli ("ᲢᲝ"), the capital-only script the rest of the portal no longer
 * uses (owner decision კ2).
 *
 * @param fallback shown when there is no name, e.g. an email's first letter.
 */
export function nameInitials(name: string | null | undefined, fallback: string): string {
  const trimmed = name?.trim();
  const letters = trimmed ? trimmed.split(/\s+/).slice(0, 2).map((part) => part[0]).join('') : fallback;
  return letters.replace(/[^\u10A0-\u10FF\u1C90-\u1CBF\u2D00-\u2D2F]/g, (letter) => letter.toUpperCase());
}
