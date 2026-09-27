/**
 * The letters on a person's avatar: the first letter of each of their first
 * two words, so "ტექნიკური ოპერატორი" reads "ᲢᲝ" in the header, the sidebar
 * and the profile page alike. The profile page once took the first two
 * letters of the name instead ("ᲢᲔ"), so the same person wore two avatars.
 *
 * @param fallback shown when there is no name, e.g. an email's first letter.
 */
export function nameInitials(name: string | null | undefined, fallback: string): string {
  const trimmed = name?.trim();
  const letters = trimmed ? trimmed.split(/\s+/).slice(0, 2).map((part) => part[0]).join('') : fallback;
  return letters.toUpperCase();
}
