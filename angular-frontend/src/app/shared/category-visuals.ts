/**
 * The icon a category is drawn with, wherever it is drawn. Its stored `icon`
 * is free text an administrator typed, and the legacy database the
 * production import copies from stores `fa_wifi` for roaming -- an
 * underscore where Font Awesome has a hyphen. Used verbatim that matched no
 * rule, so the tile, the category page and the overview all showed an empty
 * square, and nothing fell back because the value was not empty.
 *
 * Without a usable stored icon it is a plain folder. It used to be guessed
 * from keywords in the category name and in each article's title, so one
 * category wore a different icon on every card (owner decision კ7): the
 * icon is the one the administrator chose, or none in particular.
 */
export const DEFAULT_CATEGORY_ICON = 'fa-folder';

export function categoryIconClass(category: { icon?: string | null } | null | undefined): string {
  const stored = (category?.icon ?? '').trim().replace(/_/g, '-');
  return /^fa-[a-z0-9-]+$/.test(stored) ? stored : DEFAULT_CATEGORY_ICON;
}

/** Standardized "new" badge window -- the original app used two different
 *  thresholds inconsistently (4 days for the KB grid/search, 48 hours for
 *  the category-view page and dashboard tile dot). Consolidated to the
 *  4-day threshold everywhere, since that's the one documented in the
 *  visible UI copy (base-layout.html's KB page info tip). Deliberate
 *  cleanup, not literal-per-site translation -- see migration doc. */
export const NEW_BADGE_WINDOW_MS = 4 * 24 * 60 * 60 * 1000;

export function isRecentlyPublished(article: { publishedAt: string | null; createdAt: string }): boolean {
  const reference = article.publishedAt || article.createdAt;
  return Date.now() - new Date(reference).getTime() < NEW_BADGE_WINDOW_MS;
}

/** Shared by the KB bento grid and the dashboard's category grid -- both
 *  need per-category article counts from the same kind of bounded sample. */
export function buildCategoryCounts(cards: { categoryName: string }[]): Map<string, number> {
  const counts = new Map<string, number>();
  for (const card of cards) {
    counts.set(card.categoryName, (counts.get(card.categoryName) ?? 0) + 1);
  }
  return counts;
}
