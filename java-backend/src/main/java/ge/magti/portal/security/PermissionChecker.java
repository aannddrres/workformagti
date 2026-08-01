package ge.magti.portal.security;

import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import org.springframework.stereotype.Service;

/**
 * Mirrors security.py's {@code role_has_permission} (security.py:408-443),
 * simplified per the 2026-07-30 RBAC-catalog decision (migration doc §5,
 * bug #5): the DB-backed {@code Role}/{@code Permission}/
 * {@code RolePermission} tables and their disjoint colon-named catalog are
 * retired, not ported -- {@link User#getPermissions()} is now the single
 * source of truth for every permission, {@code system:audit} included.
 * That collapses the Python function's two-source check down to one.
 */
@Service
public class PermissionChecker {

    public boolean hasPermission(User user, Permission permission) {
        if (user.getRole() == Role.SYSTEM_ADMIN) {
            return true;
        }
        return user.hasPermission(permission);
    }
}
