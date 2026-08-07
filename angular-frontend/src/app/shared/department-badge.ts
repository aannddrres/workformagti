export interface DepartmentBadge {
  label: string;
  colorClass: string;
}

/**
 * News's department badge (app-renderers.js:456-469) checks
 * `target_department` against a stale English taxonomy ('Support' /
 * 'Informational') left over from an earlier seed convention, while every
 * real department value in the app today is the Georgian one used by the
 * filter dropdown itself (base-layout.html:1084-1089: 'All' / 'ტექნიკური' /
 * 'საინფო' / 'ოფისი') -- so in practice the badge almost always falls
 * through to the generic gray "საერთო" default. Rebuilt against the
 * taxonomy that's actually stored, so the badge really distinguishes
 * departments instead of silently always showing the same fallback.
 */
export function getDepartmentBadge(department: string | null | undefined): DepartmentBadge {
  switch (department) {
    case 'ტექნიკური':
      return { label: 'ტექნიკური', colorClass: 'bg-blue-50 text-blue-600 border border-blue-100' };
    case 'საინფო':
      return { label: 'საინფორმაციო', colorClass: 'bg-emerald-50 text-emerald-600 border border-emerald-100' };
    case 'ოფისი':
      return { label: 'ოფისი', colorClass: 'bg-amber-50 text-amber-600 border border-amber-100' };
    case 'All':
      return { label: 'საერთო', colorClass: 'bg-purple-50 text-purple-600 border border-purple-100' };
    default:
      return { label: department || 'საერთო', colorClass: 'bg-gray-100 text-gray-600' };
  }
}
