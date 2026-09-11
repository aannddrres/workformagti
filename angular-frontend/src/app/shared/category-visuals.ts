/**
 * Port of app-renderers.js's getCategoryIcon (lines 399-426) and
 * getCategoryCardStyles (lines 428-438) -- keyword-matching fallbacks used
 * when a category has no explicit `icon` field. Ported verbatim (same
 * keyword order/precedence) rather than redesigned, since these encode
 * real content-domain knowledge that isn't derivable from the code alone.
 */
export function getCategoryIcon(categoryName: string | null | undefined, titleText: string | null | undefined): string {
  const name = (categoryName || '').toLowerCase();
  const title = (titleText || '').toLowerCase();

  if (name.includes('როუმინგ') || title.includes('როუმინგ') || title.includes('roaming')) return 'fa-plane-up';
  if (name.includes('ინტერნეტ') || title.includes('ინტერნეტ') || title.includes('ბოჭკოვ') || title.includes('fiber') || title.includes('isp')) return 'fa-tower-cell';
  if (title.includes('wifi') || title.includes('ვაი') || title.includes('wi-fi')) return 'fa-wifi';
  if (name.includes('iptv') || name.includes('ტელევიზ') || title.includes('iptv') || title.includes('ტელევიზ') || title.includes('არხებ') || title.includes('set-top')) return 'fa-tv';
  if (title.includes('სიჩქარ') || title.includes('ტესტ') || title.includes('speed') || title.includes('speedtest')) return 'fa-gauge-high';
  if (name.includes('მობილურ') || title.includes('მობილურ') || title.includes('სიმ ბარათ') || title.includes('sim') || title.includes('ტელეფონ')) return 'fa-mobile-screen-button';
  if (title.includes('პორტირებ') || title.includes('პორტ') || title.includes('mnp')) return 'fa-arrow-right-arrow-left';
  if (name.includes('ბილინგ') || title.includes('ბილინგ') || title.includes('გადახდ') || title.includes('დავალიან') || title.includes('ფინანს') || title.includes('ინვოის')) return 'fa-file-invoice-dollar';
  if (name.includes('ლოიალობ') || title.includes('ლოიალობ') || title.includes('ქულებ') || title.includes('აქცია') || title.includes('საჩუქ') || title.includes('შეთავაზ') || title.includes('bonus')) return 'fa-gift';
  if (title.includes('უსაფრთხ') || title.includes('პოლიტიკ') || title.includes('დაცვა') || title.includes('vpn') || title.includes('2fa') || title.includes('პაროლ')) return 'fa-shield-halved';
  if (name.includes('ტექნიკურ') || title.includes('ტექნიკურ') || title.includes('ინსტრუქცი') || title.includes('კონფიგ') || title.includes('პარამეტრ') || title.includes('router')) return 'fa-screwdriver-wrench';
  if (title.includes('კომპიუტერ') || title.includes('pc') || title.includes('ლეპტოპ')) return 'fa-laptop-code';
  if (title.includes('ხმა') || title.includes('აუდიო') || title.includes('voip')) return 'fa-volume-high';
  if (title.includes('მართვა') || title.includes('cabinet') || title.includes('self-service') || title.includes('პროფილ')) return 'fa-sliders';
  if (title.includes('დომენ') || title.includes('რეგისტრაც') || title.includes('hosting') || title.includes('სერვერ')) return 'fa-server';
  if (title.includes('email') || title.includes('მეილ') || title.includes('ფოსტა')) return 'fa-envelope-open-text';
  if (name.includes('ციფრულ') || title.includes('ციფრულ') || title.includes('digital') || title.includes('app') || title.includes('apk')) return 'fa-mobile-button';
  if (name.includes('სერვის') || title.includes('მოწვევა') || title.includes('ოსტატ') || title.includes('technician')) return 'fa-helmet-safety';
  if (title.includes('ახალ') || title.includes('news') || title.includes('განახლებ')) return 'fa-bullhorn';

  return 'fa-folder-tree';
}

export interface CategoryCardStyles {
  borderHover: string;
  borderAccent: string;
  iconBg: string;
  textAccent: string;
}

export function getCategoryCardStyles(categoryName: string | null | undefined): CategoryCardStyles {
  const name = (categoryName || '').toLowerCase();
  if (name.includes('როუმინგ')) return { borderHover: 'hover:border-blue-200', borderAccent: 'border-l-4 border-l-blue-500', iconBg: 'bg-blue-50 text-blue-600', textAccent: 'group-hover:text-blue-700' };
  if (name.includes('ინტერნეტ') || name.includes('isp')) return { borderHover: 'hover:border-emerald-200', borderAccent: 'border-l-4 border-l-emerald-500', iconBg: 'bg-emerald-50 text-emerald-700', textAccent: 'group-hover:text-emerald-700' };
  if (name.includes('iptv') || name.includes('ტელევიზ')) return { borderHover: 'hover:border-purple-200', borderAccent: 'border-l-4 border-l-purple-500', iconBg: 'bg-purple-50 text-purple-600', textAccent: 'group-hover:text-purple-700' };
  if (name.includes('ტექნიკურ') || name.includes('მხარდაჭერ')) return { borderHover: 'hover:border-orange-200', borderAccent: 'border-l-4 border-l-orange-500', iconBg: 'bg-orange-50 text-orange-700', textAccent: 'group-hover:text-orange-700' };
  if (name.includes('ბილინგ') || name.includes('გადახდ')) return { borderHover: 'hover:border-teal-200', borderAccent: 'border-l-4 border-l-teal-500', iconBg: 'bg-teal-50 text-teal-600', textAccent: 'group-hover:text-teal-700' };
  if (name.includes('ლოიალობ') || name.includes('ქულებ')) return { borderHover: 'hover:border-pink-200', borderAccent: 'border-l-4 border-l-pink-500', iconBg: 'bg-pink-50 text-pink-600', textAccent: 'group-hover:text-pink-700' };
  if (name.includes('მობილურ') || name.includes('სიმ')) return { borderHover: 'hover:border-cyan-200', borderAccent: 'border-l-4 border-l-cyan-500', iconBg: 'bg-cyan-50 text-cyan-600', textAccent: 'group-hover:text-cyan-700' };
  return { borderHover: 'hover:border-brand/40', borderAccent: '', iconBg: 'bg-slate-50 text-slate-600', textAccent: 'group-hover:text-black' };
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
