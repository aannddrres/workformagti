const ICON_BY_TYPE: Record<string, string> = {
  article: 'fa-book-open',
  news: 'fa-bullhorn',
  video: 'fa-video'
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
