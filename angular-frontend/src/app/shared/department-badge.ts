export interface DepartmentBadge {
  label: string;
  colorClass: string;
}

const DEPARTMENT_LABELS: Record<string, string> = {
  All: 'ყველასთვის',
  Support: 'ტექნიკური მხარდაჭერა',
  Informational: 'საინფორმაციო',
  Administration: 'ადმინისტრაცია',
  'Content Creation': 'კონტენტის მართვა',
  'ტექნიკური': 'ტექნიკური',
  'საინფო': 'საინფორმაციო',
  'ოფისი': 'ოფისი'
};

/**
 * The database still contains both legacy English and current Georgian
 * department values. Keep those stable identifiers intact for filtering and
 * API calls, but never expose the legacy taxonomy in the Georgian interface.
 */
export function formatDepartmentLabel(department: string | null | undefined): string {
  if (!department) return 'არ არის მითითებული';
  return DEPARTMENT_LABELS[department] ?? department;
}

/**
 * News's department badge (app-renderers.js:456-469) checks
 * `target_department` against a stale English taxonomy ('Support' /
 * 'Informational') left over from an earlier seed convention, while every
 * real department value in the app today is the Georgian one used by the
 * filter dropdown itself (base-layout.html:1084-1089: 'All' / 'ტექნიკური' /
 * 'საინფო' / 'ოფისი') -- so in practice the badge almost always falls
 * through to the generic gray "all" default. Rebuilt against the
 * taxonomy that's actually stored, so the badge really distinguishes
 * departments instead of silently always showing the same fallback.
 */
export function getDepartmentBadge(department: string | null | undefined): DepartmentBadge {
  switch (department) {
    case 'ტექნიკური':
      return { label: 'ტექნიკური', colorClass: 'bg-blue-50 text-blue-600 border border-blue-100 dark:bg-blue-950/40 dark:text-blue-300 dark:border-blue-800/60' };
    // 'საინფორმაციო' is what the news form, the news filter (FE-07) and the
    // seeders store; 'საინფო' is the older spelling, kept so a row still
    // carrying it gets the same badge.
    case 'საინფორმაციო':
    case 'საინფო':
      return { label: 'საინფორმაციო', colorClass: 'bg-emerald-50 text-emerald-700 border border-emerald-100 dark:bg-emerald-950/40 dark:text-emerald-300 dark:border-emerald-800/60' };
    case 'ოფისი':
      return { label: 'ოფისი', colorClass: 'bg-amber-50 text-amber-700 border border-amber-100 dark:bg-amber-950/40 dark:text-amber-300 dark:border-amber-800/60' };
    case 'All':
      return { label: 'ყველასთვის', colorClass: 'bg-purple-50 text-purple-600 border border-purple-100 dark:bg-purple-950/40 dark:text-purple-300 dark:border-purple-800/60' };
    default:
      return { label: formatDepartmentLabel(department), colorClass: 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300' };
  }
}
