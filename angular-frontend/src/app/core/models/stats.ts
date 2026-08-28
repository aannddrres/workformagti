/** Mirrors web.KpiResponse. */
export interface KpiCounts {
  users: number;
  articles: number;
  required_readings: number;
  videos: number;
}

/** Mirrors web.ActivityPointResponse -- one bucket of the activity trend line. */
export interface ActivityPoint {
  date: string;
  count: number;
}

/** Mirrors web.TopArticleResponse. read_count is real (see TopArticleResponse's javadoc -- Python's original chart used synthetic descending placeholders here instead). */
export interface TopArticle {
  id: number;
  title: string;
  read_count: number;
}

/** Mirrors web.ComplianceStatsResponse. */
export interface ComplianceStats {
  read_percentage: number;
  unread_percentage: number;
  top_articles: TopArticle[];
}

/** Mirrors web.PopularSearchResponse -- used for both popular-searches and failed-searches. */
export interface PopularSearch {
  search_term: string;
  count: number;
}

/** Mirrors web.UserProgressItemResponse. percentage is a pre-formatted "NN%" string, matching Python's f-string. */
export interface UserProgressItem {
  user_id: number;
  user_name: string;
  department: string | null;
  read_count: number;
  required_count: number;
  percentage: string;
}

/** Mirrors web.BreakdownItemResponse. */
export interface BreakdownItem {
  label: string;
  count: number;
}

/** Mirrors stats.DashboardInsights -- the Insights Ribbon's 3 tiles. */
export interface DashboardInsights {
  global_compliance: number;
  critical_operators: number;
  total_output_volume: number;
  total_members: number;
}

/** Mirrors stats.DepartmentMember (not fetched directly -- group-users drill-down uses GroupMemberCompletion instead). */
export interface DepartmentMember {
  user_id: number;
  user_name: string;
  position: string | null;
  read_count: number;
  required_count: number;
  percentage: number;
  is_critical: boolean;
}

/** Mirrors stats.DepartmentGroupStats. */
export interface DepartmentGroupStats {
  name: string;
  full_department: string;
  member_count: number;
  compliance: number;
  output_volume: number;
  critical_count: number;
  /**
   * Always [] for a MANAGER caller -- the backend redacts the per-person rows
   * for them and keeps every aggregate (SEC-03, DepartmentStatsBuilder
   * .withoutMembers). Nothing renders this today, so a non-zero member_count
   * beside an empty members[] is expected, not missing data; use the
   * group-users drill-down (StatsService.groupUsers, itself department-scoped)
   * if per-person rows are ever needed here.
   */
  members: DepartmentMember[];
}

/** Mirrors stats.DepartmentStats -- one of the 3 whitelisted department cards. */
export interface DepartmentStats {
  name: string;
  member_count: number;
  group_count: number;
  compliance: number;
  output_volume: number;
  critical_count: number;
  is_empty: boolean;
  groups: DepartmentGroupStats[];
}

/** Mirrors stats.DepartmentDashboard -- web.getDepartmentStats's response body. */
export interface DepartmentDashboard {
  insights: DashboardInsights;
  departments: DepartmentStats[];
  generated_at: string;
}

/** Mirrors stats.CriticalOperator. */
export interface CriticalOperator {
  user_id: number;
  first_name: string;
  last_name: string;
  department: string | null;
  overdue_count: number;
}

/** Mirrors web.CriticalOperatorsResponse. */
export interface CriticalOperatorsResponse {
  operators: CriticalOperator[];
  total: number;
  generated_at: string;
}

/** Mirrors stats.GroupMemberCompletion. */
export interface GroupMemberCompletion {
  user_id: number;
  first_name: string;
  last_name: string;
  completion_percentage: number;
}

/** Mirrors web.GroupUsersResponse. */
export interface GroupUsersResponse {
  department: string;
  group_name: string;
  users: GroupMemberCompletion[];
  total: number;
}

export interface LeadershipOption {
  teamId: number;
  teamName: string;
  assignmentType: 'PRIMARY' | 'ACTING';
}

export interface LeadershipOptionsResponse {
  groups: LeadershipOption[];
  defaultTeamId: number | null;
  canExportPrimary: boolean;
}
