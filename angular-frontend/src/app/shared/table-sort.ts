import { Signal, signal } from '@angular/core';

export type SortDirection = 'asc' | 'desc';

export interface SortState {
  key: string;
  direction: SortDirection;
}

/** Pulls the comparable value for one column out of a row. */
export type SortAccessor<T> = (row: T) => string | number | Date | null | undefined;

export interface TableSort<T> {
  readonly state: Signal<SortState | null>;
  /** Same column toggles direction; a different column starts ascending. */
  toggle(key: string): void;
  /** For `[attr.aria-sort]` on the `<th>`. */
  ariaSort(key: string): 'ascending' | 'descending' | null;
  /** For the arrow in the header button. */
  icon(key: string): string;
  sort(rows: readonly T[]): T[];
}

function isMissing(value: unknown): boolean {
  return value === null || value === undefined || value === '';
}

function compare(a: unknown, b: unknown): number {
  if (a instanceof Date || b instanceof Date) {
    return new Date(a as string).getTime() - new Date(b as string).getTime();
  }
  if (typeof a === 'number' && typeof b === 'number') {
    return a - b;
  }
  // Default collation is correct for Georgian: Mkhedruli occupies one
  // contiguous, alphabetically ordered Unicode block, and Chromium has no `ka`
  // collation data to pass anyway -- see shared/ka-date.ts for the same gap.
  return String(a).localeCompare(String(b));
}

/**
 * Column sorting for a table whose rows are all in the browser.
 *
 * Not for server-paginated tables: sorting the fifty rows that happen to be on
 * screen looks like sorting the data and is not, which is worse than no sort
 * control at all. The audit log is the one that matters here -- it pages
 * through `ORDER BY timestamp DESC` on the server and has no sort parameter.
 */
export function createTableSort<T>(
  accessors: Record<string, SortAccessor<T>>,
  initial: SortState | null = null,
): TableSort<T> {
  const state = signal<SortState | null>(initial);

  return {
    state: state.asReadonly(),
    toggle(key: string): void {
      state.update((current) =>
        current?.key === key
          ? { key, direction: current.direction === 'asc' ? 'desc' : 'asc' }
          : { key, direction: 'asc' },
      );
    },
    ariaSort(key: string) {
      const current = state();
      if (current?.key !== key) return null;
      return current.direction === 'asc' ? 'ascending' : 'descending';
    },
    icon(key: string): string {
      const current = state();
      if (current?.key !== key) return 'fa-sort';
      return current.direction === 'asc' ? 'fa-sort-up' : 'fa-sort-down';
    },
    sort(rows: readonly T[]): T[] {
      const current = state();
      const accessor = current && accessors[current.key];
      if (!current || !accessor) return [...rows];

      const factor = current.direction === 'asc' ? 1 : -1;
      // A stable sort keeps the server's order as the tie-breaker.
      return [...rows].sort((a, b) => {
        const left = accessor(a);
        const right = accessor(b);
        // Blanks sort last in *both* directions, so the direction factor must
        // not reach them: a row missing a value is not the smallest one, it is
        // unknown, and flipping it to the top on a descending sort hides the
        // rows the reader actually asked for.
        if (isMissing(left) || isMissing(right)) {
          if (isMissing(left) && isMissing(right)) return 0;
          return isMissing(left) ? 1 : -1;
        }
        return factor * compare(left, right);
      });
    },
  };
}
