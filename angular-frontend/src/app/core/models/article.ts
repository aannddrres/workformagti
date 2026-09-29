/** Mirrors web.ArticleSummaryResponse -- the list-view shape (no content). */
export interface ArticleSummary {
  id: number;
  title: string;
  category_id: number | null;
  category_name: string | null;
  tags: string | null;
  target_departments: string[];
  status: string;
  published_at: string | null;
  created_at: string;
  read_time: number;
  audience_profile: string | null;
  visible_to_tech_info: boolean;
  visible_to_service_center: boolean;
  is_draft: boolean;
}

/** Mirrors web.RecentlyViewedItemResponse. */
export interface RecentlyViewedItem {
  article_id: number;
  title: string;
  viewed_at: string;
}

export interface RelatedArticle {
  id: number;
  title: string;
  category_id: number | null;
  tags: string | null;
}

/** Mirrors web.ArticleRequest -- shared create/update body. */
export interface ArticleRequest {
  title: string;
  content: string;
  category_id: number;
  tags: string | null;
  target_departments: string[];
  status: string;
  youtube_id?: string | null;
  published_at: string | null;
  attachment_url: string | null;
  last_verified_at?: string | null;
  audience_profile: string;
  visible_to_tech_info: boolean;
  visible_to_service_center: boolean;
  is_draft: boolean;
  quiz_enabled: boolean;
}

export interface ArticleCommandRequest {
  article: ArticleRequest;
  mandatory: boolean;
  due_date: string | null;
  target_department: string;
  quiz: { questions: import('./quiz-admin').QuizQuestionAdmin[] } | null;
}

/** Mirrors web.ArticleBulkArchiveResponse. */
export interface ArticleBulkArchiveResponse {
  updated: number;
  status: string;
  skipped_ids: number[];
}

/** The editorial states a batch can be moved between. Mirrors the backend pattern. */
export type ArticleBulkStatus = 'draft' | 'published' | 'archived';

/**
 * Mirrors web.ArticleBulkResponse.
 *
 * skipped_ids is not an error list -- an id already in the requested state, or
 * one that no longer exists, is reported rather than failing the batch. The UI
 * says so instead of claiming everything worked.
 */
export interface ArticleBulkResponse {
  updated: number;
  skipped_ids: number[];
}

/** Mirrors web.ArticleResponse -- the full/detail shape (has content, no category_name). */
export interface Article {
  id: number;
  title: string;
  content: string;
  category_id: number | null;
  tags: string | null;
  target_departments: string[];
  status: string;
  youtube_id: string;
  published_at: string | null;
  attachment_url: string | null;
  author_id: number | null;
  last_verified_at: string | null;
  audience_profile: string | null;
  visible_to_tech_info: boolean;
  visible_to_service_center: boolean;
  is_draft: boolean;
  quiz_enabled: boolean;
  created_at: string;
  updated_at: string;
  version: number;
  read_time: number;
}
