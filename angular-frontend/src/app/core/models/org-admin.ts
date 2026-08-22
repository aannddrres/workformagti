export interface OrgStructure {
  departments: OrgDepartment[];
}

export interface OrgDepartment {
  id: number;
  stable_key: string;
  name: string;
  is_active: boolean;
  teams: OrgTeam[];
}

export interface OrgTeam {
  id: number;
  stable_key: string | null;
  name: string;
  is_active: boolean;
  member_count: number;
}

export type LeadershipScope = 'DEPARTMENT' | 'GROUP';
export type LeadershipAssignmentType = 'PRIMARY' | 'ACTING';
export type LeadershipAssignmentSource = 'MANUAL' | 'BACKFILL' | 'AD_SYNC';

export interface LeadershipAssignment {
  id: number;
  user_id: number;
  user_name: string;
  user_email: string;
  scope: LeadershipScope;
  department_id: number | null;
  department_name: string | null;
  team_id: number | null;
  team_name: string | null;
  assignment_type: LeadershipAssignmentType;
  is_active: boolean;
  started_at: string;
  ended_at: string | null;
  created_by: number | null;
  source: LeadershipAssignmentSource;
}

export interface LeadershipAssignmentCreateRequest {
  user_id: number;
  department_id: number | null;
  team_id: number | null;
  assignment_type: LeadershipAssignmentType;
}

export interface PolicyShadowDecision {
  unexercised: boolean;
  agreed: number;
  disagreed: number;
}

export interface PolicyShadowSnapshot {
  decisions: Record<string, PolicyShadowDecision>;
  generated_at: string;
}

export interface OrgBackfillIssue {
  kind: string;
  user_id: number | null;
  subject: string | null;
  detail: string;
}

export interface OrgBackfillReport {
  groups_to_create: number;
  memberships: number;
  leaders_resolved: number;
  needs_a_decision: number;
  blocks_cutover: boolean;
  issues: OrgBackfillIssue[];
  report: string;
  generated_at: string;
}
