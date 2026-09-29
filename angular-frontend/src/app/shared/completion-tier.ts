/**
 * One scale for every completion percentage (owner decision კ13, 2026-09-29):
 * 80% and up is green, 30-79% amber, under 30% red.
 *
 * The team screen alone had three: its bars went green at 80, the number next
 * to the same bar went green only at 100 and yellow above 50, and the member
 * list's bars used a third pair -- so one group could show a green bar beside
 * a yellow "85%". The lowest tier also borrowed the brand red of the primary
 * button, which made "behind" look like "click here".
 *
 * Colour is never the only signal: the percentage is always printed, and the
 * lowest tier adds an icon (`completionIcon`).
 */
export type CompletionTier = 'good' | 'middle' | 'low' | 'none';

export function completionTier(percentage: number | string | null | undefined, hasRequirement = true): CompletionTier {
  const value = typeof percentage === 'string' ? parseFloat(percentage) : percentage;
  if (!hasRequirement || value === null || value === undefined || Number.isNaN(value)) return 'none';
  if (value >= 80) return 'good';
  if (value >= 30) return 'middle';
  return 'low';
}

const TEXT: Record<CompletionTier, string> = {
  good: 'text-emerald-700 dark:text-emerald-400',
  middle: 'text-amber-700 dark:text-amber-400',
  low: 'text-red-700 dark:text-red-400',
  none: 'text-slate-600 dark:text-slate-400'
};

const BAR: Record<CompletionTier, string> = {
  good: 'bg-emerald-500',
  middle: 'bg-amber-400',
  low: 'bg-red-600 dark:bg-red-500',
  none: 'bg-slate-300 dark:bg-slate-600'
};

export function completionTextClass(tier: CompletionTier): string {
  return TEXT[tier];
}

export function completionBarClass(tier: CompletionTier): string {
  return BAR[tier];
}

/** A Font Awesome class for the tier that needs attention, or null. */
export function completionIcon(tier: CompletionTier): string | null {
  return tier === 'low' ? 'fa-triangle-exclamation' : null;
}
