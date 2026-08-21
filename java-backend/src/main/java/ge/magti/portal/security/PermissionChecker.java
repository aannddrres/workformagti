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
 * retired, not ported. Since Phase 6, the effective decision is composed by
 * {@link CapabilityService} from role defaults plus explicit
 * {@code ALLOW}/{@code DENY} overrides. The legacy
 * {@link User#getPermissions()} list remains only for compatibility until the
 * Phase 7 response-contract cleanup.
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
     * Production policy engine. The no-argument fallback exists only for
     * DB-free tests that construct this checker directly; Spring always
     * injects the capability service.
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
        if (capabilityService != null) {
            // Phase 6 cutover: role defaults plus explicit ALLOW/DENY now
            // decide. users.permissions remains only as a compatibility
            // column until the Phase 7 response-contract cleanup.
            return capabilityService.hasCapability(user, permission);
        }
        // DB-free legacy unit tests construct this service directly. The
        // Spring application always injects CapabilityService above.
        return user.hasPermission(permission);
    }
}
