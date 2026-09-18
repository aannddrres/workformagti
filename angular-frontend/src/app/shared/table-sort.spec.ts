import { describe, expect, it } from 'vitest';
import { createTableSort } from './table-sort';

interface Row {
  name: string;
  count: number;
  when: string | null;
}

const rows: Row[] = [
  { name: 'ბესიკი', count: 3, when: '2026-03-02T00:00:00Z' },
  { name: 'ანა', count: 10, when: null },
  { name: 'გიორგი', count: 1, when: '2026-01-05T00:00:00Z' },
];

function make() {
  return createTableSort<Row>({
    name: (r) => r.name,
    count: (r) => r.count,
    when: (r) => r.when,
  });
}

describe('createTableSort', () => {
  it('leaves the rows alone until a column is chosen', () => {
    expect(make().sort(rows).map((r) => r.name)).toEqual(['ბესიკი', 'ანა', 'გიორგი']);
  });

  it('sorts Georgian names alphabetically without locale data', () => {
    const sort = make();
    sort.toggle('name');
    expect(sort.sort(rows).map((r) => r.name)).toEqual(['ანა', 'ბესიკი', 'გიორგი']);
  });

  it('toggles the same column and restarts ascending on a different one', () => {
    const sort = make();
    sort.toggle('count');
    expect(sort.sort(rows).map((r) => r.count)).toEqual([1, 3, 10]);

    sort.toggle('count');
    expect(sort.sort(rows).map((r) => r.count)).toEqual([10, 3, 1]);

    sort.toggle('name');
    expect(sort.state()).toEqual({ key: 'name', direction: 'asc' });
  });

  it('keeps blanks last whichever way the column runs', () => {
    const sort = make();
    sort.toggle('when');
    expect(sort.sort(rows).at(-1)?.when).toBeNull();

    sort.toggle('when');
    expect(sort.sort(rows).at(-1)?.when).toBeNull();
  });

  it('reports the state an assistive technology reads', () => {
    const sort = make();
    expect(sort.ariaSort('name')).toBeNull();
    expect(sort.icon('name')).toBe('fa-sort');

    sort.toggle('name');
    expect(sort.ariaSort('name')).toBe('ascending');
    expect(sort.icon('name')).toBe('fa-sort-up');
    expect(sort.ariaSort('count')).toBeNull();
  });

  it('does not mutate the rows it is given', () => {
    const sort = make();
    sort.toggle('name');
    sort.sort(rows);
    expect(rows.map((r) => r.name)).toEqual(['ბესიკი', 'ანა', 'გიორგი']);
  });
});
