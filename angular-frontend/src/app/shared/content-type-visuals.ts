/**
 * One icon per kind of content, the same in the menu, lists, search and
 * favourites (owner decision კ7): article a book, news a newspaper, video a
 * play button. The bullhorn belongs to announcements alone; news used it too,
 * so a news item and an announcement looked like the same thing.
 */
const ICON_BY_TYPE: Record<string, string> = {
  article: 'fa-book-open',
  news: 'fa-newspaper',
  video: 'fa-circle-play'
};

const ROUTE_BY_TYPE: Record<string, string> = {
  article: '/article',
  news: '/news',
  video: '/videos'
};

export function iconForContentType(itemType: string): string {
  return ICON_BY_TYPE[itemType] || 'fa-file';
}

export function detailRouteFor(itemType: string, itemId: number): string[] | null {
  const base = ROUTE_BY_TYPE[itemType];
  return base ? [base, String(itemId)] : null;
}
