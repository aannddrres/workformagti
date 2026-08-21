/**
 * The 8 real, working permission values, ported from Permission.java's
 * enum + DEFAULTS_BY_ROLE -- NOT the 9 colon-named checkbox values
 * (`content:editor`, `reports:view_global`, ...) the Python edit-user
 * modal actually renders. Confirmed live (routers/users.py:460-465 vs.
 * base-layout.html's checkbox `value` attributes): those colon strings
 * never match the backend's dotted whitelist, so every save from that
 * screen has always failed with a 400 "უცნობი უფლება(ები)" in the live
 * Python app. Fixed here with the user's explicit sign-off -- this
 * catalog is the one Java's PermissionsUpdateRequest actually accepts.
 */
export interface PermissionOption {
  value: string;
  label: string;
}

export interface PermissionGroup {
  heading: string;
  options: PermissionOption[];
}

export const PERMISSION_GROUPS: PermissionGroup[] = [
  {
    heading: 'system',
    options: [
      // 'users.manage' removed with its backend counterpart (audit SEC-06):
      // it was granted only to SYSTEM_ADMIN, and PermissionChecker bypasses
      // every check for that role, so the switch could never affect a
      // decision no matter what it was set to.
      { value: 'system.audit', label: 'system_audit' }
    ]
  },
  {
    heading: 'content',
    options: [
      // 'articles.view' was removed with its backend counterpart (audit
      // SEC-06): it was never enforced, and could not safely be, since
      // OPERATOR holds no permissions at all -- gating reads on it would
      // have closed the knowledge base to everyone who uses it. The backend
      // now rejects it as an unknown permission, so offering the switch here
      // would fail the save.
      { value: 'articles.edit', label: 'articles_edit' },
      { value: 'articles.publish', label: 'articles_publish' },
      { value: 'articles.archive', label: 'articles_archive' },
      { value: 'videos.archive', label: 'videos_archive' },
      { value: 'content.manage', label: 'content_manage' }
    ]
  },
  {
    heading: 'compliance_reports',
    options: [
      { value: 'compliance.assign', label: 'compliance_assign' },
      { value: 'reports.export', label: 'reports_export' }
    ]
  }
];

/** Mirrors Permission.java's DEFAULTS_BY_ROLE exactly. */
const DEFAULTS_BY_ROLE: Record<string, string[]> = {
  operator: [],
  manager: ['reports.export', 'system.audit'],
  content_admin: [
    'articles.edit', 'articles.publish', 'articles.archive',
    'videos.archive', 'content.manage', 'compliance.assign', 'system.audit'
  ],
  admin: [
    'articles.edit', 'articles.publish', 'articles.archive',
    'videos.archive', 'content.manage', 'compliance.assign', 'reports.export', 'system.audit'
  ]
};

export function defaultPermissionsForRole(role: string): string[] {
  return DEFAULTS_BY_ROLE[role] ?? [];
}
