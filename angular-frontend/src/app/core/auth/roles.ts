/**
 * The role sets the UI gates on, in one place.
 *
 * These four lists were declared independently in app.routes.ts,
 * messaging-page.ts and dashboard-page.ts. The values agreed, so nothing was
 * broken -- but adding a role meant finding all three, and missing one splits
 * the app against itself in a way that is hard to read as a bug: the route
 * lets someone in and the page hides its controls, or the reverse.
 *
 * (messaging-page.ts is gone since -- the targeted-message feature was
 * replaced by durable broadcasts -- so two of the three copies remain to
 * point at. The reason for one home did not go with it.)
 *
 * This is the frontend twin of SEC-13 on the backend, where five sites
 * answered "which users may a manager see" independently until ManagerScope
 * became the single answer.
 *
 * These gate the UI only. Authorization is the backend's: every endpoint
 * re-reads the caller's role from the database on each request
 * (JwtAuthenticationFilter). A wrong list here shows or hides a control; it
 * does not grant access.
 *
 * Typed `readonly string[]` rather than `as const`: the literal-union element
 * type `as const` produces makes `.includes(user.role)` a compile error,
 * because `role` arrives from the API as a plain string. `readonly` still
 * stops anyone pushing onto a shared list.
 */

/** Roles that manage people: team statistics, and sending direct messages. */
export const MANAGER_ROLES: readonly string[] = ['admin', 'manager'];

/**
 * Everyone above an operator.
 *
 * Used as a *deny* list on /reading: mandatory reading is assigned to
 * operators, and management is exempt. Mirrors MANAGEMENT_ROLES in
 * compliance_utils.py, which is what actually decides who a required reading
 * is created for.
 */
export const MANAGEMENT_ROLES: readonly string[] = ['admin', 'content_admin', 'manager'];

/** Roles that administer content: the /admin area, and broadcast messages. */
export const ADMIN_OR_CONTENT_ADMIN: readonly string[] = ['admin', 'content_admin'];

/**
 * System administration only -- user management and role assignment.
 *
 * Deliberately not ADMIN_OR_CONTENT_ADMIN: a content admin edits content, not
 * the people who may edit it.
 */
export const ADMIN_ONLY: readonly string[] = ['admin'];
