/** Mirrors web.CurrentUserResponse -- GET /api/users/me. */
export interface CurrentUserProfile {
  id: number;
  email: string;
  name: string;
  department: string;
  position: string | null;
  phone: string | null;
  role: string;
  team_id: number | null;
  is_active: boolean;
  last_active: string | null;
  read_count: number | null;
  required_count: number | null;
  progress_percentage: number | null;
  card_style: string | null;
  permissions: string[];
  can_view_audit_log: boolean;
}
