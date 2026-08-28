import { ADMIN_ONLY, ADMIN_OR_CONTENT_ADMIN, MANAGEMENT_ROLES, MANAGER_ROLES } from './roles';

/**
 * FE-09. These four lists lived in three files at once -- app.routes.ts,
 * messaging-page.ts and dashboard-page.ts -- each declaring its own copy.
 * They agreed, so nothing was broken; the cost was that adding a role meant
 * finding all three, and missing one splits the app against itself: the route
 * admits someone the page then shows nothing to, or the reverse.
 *
 * Consolidating them removed the drift. This pins the values so the
 * consolidation is not silently undone, and states the containment the UI
 * assumes, which is the part a future role would most easily break.
 */
describe('role sets', () => {
  it('are exactly the sets the three call sites used before they were merged', () => {
    expect([...MANAGER_ROLES]).toEqual(['admin', 'manager']);
    expect([...MANAGEMENT_ROLES]).toEqual(['admin', 'content_admin', 'manager']);
    expect([...ADMIN_OR_CONTENT_ADMIN]).toEqual(['admin', 'content_admin']);
    expect([...ADMIN_ONLY]).toEqual(['admin']);
  });

  it('nest the way the routes assume', () => {
    // /admin/users sits inside /admin, so anyone who clears the inner gate
    // must clear the outer one -- otherwise the route is unreachable.
    for (const role of ADMIN_ONLY) expect(ADMIN_OR_CONTENT_ADMIN).toContain(role);
    // MANAGEMENT_ROLES is the deny list on /reading. Both narrower sets fall
    // inside it, so no one who administers content or a team is also being
    // assigned mandatory reading.
    for (const role of ADMIN_OR_CONTENT_ADMIN) expect(MANAGEMENT_ROLES).toContain(role);
    for (const role of MANAGER_ROLES) expect(MANAGEMENT_ROLES).toContain(role);
  });

  it('always include the system administrator', () => {
    // The backend grants a system admin every permission by definition
    // (PermissionChecker's bypass). A UI list that omitted them would hide a
    // control the API would happily serve.
    for (const set of [MANAGER_ROLES, MANAGEMENT_ROLES, ADMIN_OR_CONTENT_ADMIN, ADMIN_ONLY]) {
      expect(set).toContain('admin');
    }
  });

  it('names an operator in none of them', () => {
    // Every one of these gates something an operator must not reach. If a
    // future edit lets "operator" in here, it is a privilege change, not a
    // refactor, and this should fail loudly.
    for (const set of [MANAGER_ROLES, MANAGEMENT_ROLES, ADMIN_OR_CONTENT_ADMIN, ADMIN_ONLY]) {
      expect(set).not.toContain('operator');
    }
  });
});
