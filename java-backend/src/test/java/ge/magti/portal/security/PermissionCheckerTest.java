package ge.magti.portal.security;

import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionCheckerTest {

    private final PermissionChecker checker = new PermissionChecker();

    @Test
    void systemAdminBypassesEverythingRegardlessOfPermissionsList() {
        User admin = new User();
        admin.setRole(Role.SYSTEM_ADMIN);
        admin.setPermissions(Set.of());

        assertTrue(checker.hasPermission(admin, Permission.USERS_MANAGE));
    }

    @Test
    void nonAdminNeedsThePermissionExplicitlyGranted() {
        User operator = new User();
        operator.setRole(Role.OPERATOR);
        operator.setPermissions(Set.of(Permission.ARTICLES_VIEW.value()));

        assertTrue(checker.hasPermission(operator, Permission.ARTICLES_VIEW));
        assertFalse(checker.hasPermission(operator, Permission.USERS_MANAGE));
    }
}
