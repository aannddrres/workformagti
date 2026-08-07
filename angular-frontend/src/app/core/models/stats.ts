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
