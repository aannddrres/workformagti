/** Mirrors web.ArticleHistoryItemResponse (java-backend) field-for-field. */
export interface ArticleHistoryItem {
  id: number;
  title: string;
  content: string;
  updated_at: string;
  author_name: string;
  version_id: number | null;
}

/** CLOB-free metadata returned by GET .../history-summary. */
export interface ArticleHistorySummaryItem {
  id: number;
  title: string;
  updated_at: string;
  author_name: string;
  version_id: number | null;
}

/** Mirrors web.ArticleDiffResponse (java-backend) field-for-field. */
export interface ArticleDiff {
  html: string;
  added: number;
  removed: number;
  base_version: number;
  compare_version: number;
  version_id: number;
}

/** Mirrors web.ArticleVersionItemResponse (java-backend) field-for-field --
 *  the GET .../versions shape used by the reader-facing "ვერსიების
 *  ისტორია" overlay, distinct from ArticleHistoryItem's admin-only
 *  GET .../history shape (no raw content here, and history_id can be
 *  null-free since /versions self-heals missing rows server-side). */
export interface ArticleVersionItem {
  version: number;
  title: string;
  updated_at: string;
  author_name: string | null;
  history_id: number;
}
