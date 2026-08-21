/** Mirrors web.UserResponse -- one row of GET /api/users. */
export interface AdminUser {
  id: number;
  email: string;
  name: string;
  department: string | null;
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
  permission_overrides: PermissionOverride[];
  lock_version: number;
}

export type PermissionOverrideState = 'ALLOW' | 'DENY';
export type PermissionDeltaState = PermissionOverrideState | 'INHERIT';

export interface PermissionOverride {
  permission: string;
  state: PermissionOverrideState;
}

export interface PermissionOverrideDelta {
  permission: string;
  state: PermissionDeltaState;
}

export interface PermissionsDeltaRequest {
  lock_version: number;
  overrides: PermissionOverrideDelta[];
}

/** Mirrors web.GroupLeaderResponse -- GET /api/admin/group-leaders. */
export interface GroupLeader {
  id: number;
  name: string;
}

/** Mirrors web.UserCreateAdminRequest -- POST /api/users. */
export interface UserCreateRequest {
  email: string;
  name: string;
  department: string;
  position?: string | null;
  phone?: string | null;
  role: string;
  password: string;
  team_id?: number | null;
}

/** Mirrors web.UserAdminUpdateRequest -- PUT /api/users/{id}. */
export interface UserAdminUpdateRequest {
  role: string;
  department: string | null;
  phone?: string | null;
  position: string | null;
  team_id?: number | null;
}

/** Mirrors web.BulkRoleReassignResponse -- POST /api/admin/roles/bulk-reassign. */
export interface BulkRoleReassignResponse {
  new_role: string;
  changed: number;
  skipped: number;
  requested: number;
}
