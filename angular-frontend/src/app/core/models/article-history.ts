/** Mirrors web.ArticleHistoryItemResponse (java-backend) field-for-field. */
export interface ArticleHistoryItem {
  id: number;
  title: string;
  content: string;
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
