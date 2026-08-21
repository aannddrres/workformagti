package ge.magti.portal.security;

import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Mirrors security.py's {@code role_has_permission} (security.py:408-443),
 * simplified per the 2026-07-30 RBAC-catalog decision (migration doc §5,
 * bug #5): the DB-backed {@code Role}/{@code Permission}/
 * {@code RolePermission} tables and their disjoint colon-named catalog are
 * retired, not ported -- {@link User#getPermissions()} is now the single
 * source of truth for every permission, {@code system:audit} included.
 * That collapses the Python function's two-source check down to one.
 *
 * <h2>SYSTEM_ADMIN bypasses every permission check. Read this before adding one.</h2>
 *
 * The first line of {@link #hasPermission} returns {@code true} for
 * SYSTEM_ADMIN regardless of what is stored on the user. That is a
 * defensible "root role" design, but it has a consequence that is easy to
 * miss and was missed: <b>a permission whose only holder is SYSTEM_ADMIN can
 * never do anything.</b> The switch renders, saves, persists -- and is never
 * consulted, because the one role it applies to skips the consultation.
 *
 * <p>That is exactly how {@code users.manage} came to be removed during the
 * SEC-06 work rather than enforced (see {@link Permission}'s comment). It was
 * granted only to SYSTEM_ADMIN, and every endpoint it would have guarded also
 * required the SYSTEM_ADMIN role, so it was structurally incapable of
 * affecting a decision. The audit finding described it as "never consulted";
 * the deeper reason is here.
 *
 * <p>So: a new permission is only meaningful if a role that is NOT
 * SYSTEM_ADMIN can hold it and be refused. {@code compliance.assign} passes
 * that test (CONTENT_ADMIN holds it and is genuinely refused without it);
 * {@code articles.view} and {@code users.manage} did not.
 * {@code PermissionEnforcementCoverageTest} catches a permission nobody
 * checks; it cannot catch one that is checked but can never be false, which
 * is why this is written down.
 *
 * <p><b>Open decision for the owner</b>, deliberately not taken here: drop
 * this bypass so permissions bind for admins too. It would make the whole
 * catalog honest, but any SYSTEM_ADMIN row with an incomplete persisted
 * permission set would silently lose abilities the moment it deployed, so it
 * needs a look at real data first.
 */
@Service
public class PermissionChecker {

    /**
     * Phase 3 shadow only -- nullable, and null means "do not measure".
     *
     * <p>The no-argument constructor below is kept so the several unit tests
     * that build this directly stay unchanged: a measurement being added is
     * not a reason to touch five test files, and a test that measures nothing
     * is testing the same rule the application enforces.
     */
    private final CapabilityService capabilityService;

    public PermissionChecker() {
        this(null);
    }

    @Autowired
    public PermissionChecker(CapabilityService capabilityService) {
        this.capabilityService = capabilityService;
    }

    public boolean hasPermission(User user, Permission permission) {
        // See the class javadoc: this line is why a SYSTEM_ADMIN-only
        // permission can never be enforced.
        if (user.getRole() == Role.SYSTEM_ADMIN) {
            return true;
        }
        boolean granted = user.hasPermission(permission);
        if (capabilityService != null) {
            // Records what the composed role-default-plus-override rule would
            // answer, and returns the stored-list answer regardless. Phase 6
            // swaps which one decides.
            return capabilityService.shadowCompare("capability", user, permission, granted);
        }
        return granted;
    }
}
