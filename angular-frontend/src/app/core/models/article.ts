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
