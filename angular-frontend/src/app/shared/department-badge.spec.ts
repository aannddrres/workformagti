import { getDepartmentBadge } from './department-badge';

/**
 * The information department is stored as 'საინფორმაციო' -- the news form, the
 * news filter (FE-07) and the seeders all write it -- but the badge only knew
 * the older 'საინფო', so that department's current news wore the grey
 * fallback. And no badge had a `dark:` pair, so each stayed a pale light-mode
 * pill on the dark theme.
 */
describe('getDepartmentBadge', () => {
  it('gives the stored information department its own badge, not the grey fallback', () => {
    expect(getDepartmentBadge('საინფორმაციო')).toEqual(getDepartmentBadge('საინფო'));
    expect(getDepartmentBadge('საინფორმაციო').colorClass).toContain('bg-emerald-50');
  });

  it('pairs every badge with dark-theme colours', () => {
    for (const department of ['All', 'ტექნიკური', 'საინფორმაციო', 'საინფო', 'ოფისი', 'Support', null]) {
      const { colorClass } = getDepartmentBadge(department);
      expect(colorClass, String(department)).toMatch(/(^| )dark:bg-/);
      expect(colorClass, String(department)).toMatch(/(^| )dark:text-/);
    }
  });

  it('keeps the labels the screen already shows', () => {
    expect(getDepartmentBadge('All').label).toBe('საერთო');
    expect(getDepartmentBadge('საინფორმაციო').label).toBe('საინფორმაციო');
    expect(getDepartmentBadge('Support').label).toBe('ტექნიკური მხარდაჭერა');
    expect(getDepartmentBadge(null).label).toBe('არ არის მითითებული');
  });
});
