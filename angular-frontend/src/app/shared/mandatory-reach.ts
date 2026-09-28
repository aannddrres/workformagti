import { MandatoryAddressees, MandatoryDepartmentRow } from '../core/models/required-reading';
import { departmentMatches } from './department-matcher';

/**
 * When an article's readers can open it, from its status and schedule: now,
 * at a scheduled moment still ahead, or not while it stays as it is. The
 * lifecycle half of ArticleVisibility, which decides whether a mandatory
 * reading is in force (PO-40); the backend stays the authority.
 */
export type ArticleReach = 'now' | 'later' | 'never';

export function articleReach(status: string, scheduledAt: string | null, now: Date = new Date()): ArticleReach {
  if (status === 'published') {
    return 'now';
  }
  if (status === 'scheduled' && scheduledAt) {
    return new Date(scheduledAt) <= now ? 'now' : 'later';
  }
  return 'never';
}

/** What a change to a mandatory article does to the people it binds. */
export interface MandatoryLoss {
  /** Bound now or at publication, and not after the change. */
  total: number;
  /** Of those, already confirmed: the confirmations stay evidence. */
  confirmed: number;
  /** The departments losing it, with how many in each. */
  departments: { department: string; count: number }[];
  /** Named people losing it, when the caller may see names at all. */
  names: string[];
}

/**
 * Who a change takes out of a mandatory article's reach (PO-40): everyone it
 * binds when the article stops being readable, or the people whose department
 * the new audience no longer covers. Bound-at-publication people lose it when
 * the article will never be published as it stands, or leaves their department.
 */
export function mandatoryLoss(
  current: MandatoryAddressees,
  next: { reach: ArticleReach; departments: readonly string[] }
): MandatoryLoss {
  const covered = (department: string) => next.departments.some((target) => departmentMatches(department, target));
  const lostNow = (row: MandatoryDepartmentRow) => (next.reach !== 'now' || !covered(row.department) ? row.in_force : 0);
  const lostLater = (row: MandatoryDepartmentRow) =>
    next.reach === 'never' || !covered(row.department) ? row.pending : 0;

  const departments = current.departments
    .map((row) => ({ row, count: lostNow(row) + lostLater(row) }))
    .filter(({ count }) => count > 0);
  const losing = new Set(departments.map(({ row }) => row.department));

  return {
    total: departments.reduce((sum, { count }) => sum + count, 0),
    confirmed: departments.reduce((sum, { row }) => sum + (lostNow(row) > 0 ? row.read : 0), 0),
    departments: departments.map(({ row, count }) => ({ department: row.department, count })),
    names: current.addressees
      .filter((person) => losing.has(person.department))
      .filter((person) => {
        const row = current.departments.find((r) => r.department === person.department);
        return row !== undefined && (person.pending ? lostLater(row) > 0 : lostNow(row) > 0);
      })
      .map((person) => person.user_name)
  };
}

/**
 * The lines a warning lists: names when there are any, capped with how many
 * more; otherwise each department and its count.
 */
export function lossLines(loss: MandatoryLoss, more: (count: number) => string, limit = 10): string[] {
  if (loss.names.length > 0) {
    const shown = loss.names.slice(0, limit);
    return loss.total > shown.length ? [...shown, more(loss.total - shown.length)] : shown;
  }
  return loss.departments.map(({ department, count }) => `${department} — ${count}`);
}
