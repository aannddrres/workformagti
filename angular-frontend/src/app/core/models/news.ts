/** Mirrors web.NewsSummaryResponse -- the list-view shape (no content). */
export interface NewsSummary {
  id: number;
  title: string;
  target_department: string;
  attachment_url: string | null;
  created_at: string;
  version: number;
  visible_to_tech_info: boolean;
  visible_to_service_center: boolean;
  is_archived: boolean;
  expires_at: string | null;
  is_draft: boolean;
  author_id: number | null;
}
