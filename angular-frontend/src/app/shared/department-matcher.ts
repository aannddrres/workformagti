/**
 * Frontend mirror of the backend's DepartmentMatcher (audit FE-07).
 *
 * The department strings this app filters on are free text with an optional
 * sub-group -- "ტექნიკური — ჯგუფი 03" -- and the backend has one shared rule
 * for deciding whether such a string belongs to a department. The frontend
 * had no equivalent: news-page compared with `!==`, so selecting
 * "ტექნიკური" hid every item assigned to a sub-group of it, and the
 * operators in those groups are precisely the people the filter is for.
 *
 * Kept deliberately small and pure so it can be tested directly. If the
 * backend's rule ever changes, department-matcher.spec.ts is where the two
 * are pinned together.
 */

/** Em dash. The canonical delimiter the backend writes (DepartmentMatcher.CANONICAL_DELIMITER). */
const CANONICAL_DELIMITER = '—';
const GROUP_KEYWORD = 'ჯგუფი';
const DASH_PATTERN = /\s*[-–—]\s*/;
const TRAILING_DASH_RUN = /[\s\-–—]+$/;
const WHITESPACE_RUN = /[ \t]+/g;

/** Collapses whitespace runs and trims, leaving dashes alone -- DepartmentMatcher.normalize. */
export function normalizeDepartment(raw: string | null | undefined): string {
  return (raw ?? '').trim().replace(WHITESPACE_RUN, ' ');
}

/**
 * The department without its sub-group: "ტექნიკური — ჯგუფი 03" -> "ტექნიკური".
 * Mirrors DepartmentMatcher.splitGroup's priority order exactly -- em dash
 * first, then the "ჯგუფი" keyword, then any dash.
 */
export function departmentPrefix(raw: string | null | undefined): string {
  const value = normalizeDepartment(raw);
  if (!value) {
    return '';
  }

  const delimiterIndex = value.indexOf(CANONICAL_DELIMITER);
  if (delimiterIndex >= 0) {
    return value.slice(0, delimiterIndex).trim();
  }

  const keywordIndex = value.indexOf(GROUP_KEYWORD);
  if (keywordIndex > 0) {
    return value.slice(0, keywordIndex).replace(TRAILING_DASH_RUN, '');
  }

  const parts = value.split(DASH_PATTERN);
  if (parts.length >= 2) {
    return parts[0].trim();
  }

  return value;
}

/**
 * True when `department` belongs to `target`. Mirrors
 * DepartmentMatcher.matches: "All" matches everything, then exact equality,
 * then the target matching the department's prefix.
 */
export function departmentMatches(department: string | null | undefined, target: string | null | undefined): boolean {
  if (!target) {
    return true;
  }
  if (target === 'All') {
    return true;
  }
  if (department === target) {
    return true;
  }
  return target === departmentPrefix(department);
}
