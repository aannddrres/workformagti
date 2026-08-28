package ge.magti.portal.content;

import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegalHoldAuthorityTest {

    @Test
    void emptyConfigurationFailsClosedForEveryRole() {
        LegalHoldAuthority authority = new LegalHoldAuthority("");

        assertFalse(authority.canManage(user("admin@example.test", Role.SYSTEM_ADMIN, true)));
        assertFalse(authority.canManage(user("content@example.test", Role.CONTENT_ADMIN, true)));
    }

    @Test
    void onlyExactActiveNamedIdentityIsAuthorizedCaseInsensitively() {
        LegalHoldAuthority authority = new LegalHoldAuthority(
                " approved@example.test, second@example.test ");

        assertTrue(authority.canManage(user("APPROVED@example.test", Role.SYSTEM_ADMIN, true)));
        assertFalse(authority.canManage(user("other@example.test", Role.SYSTEM_ADMIN, true)));
        assertFalse(authority.canManage(user("approved@example.test", Role.SYSTEM_ADMIN, false)));
    }

    private static User user(String email, Role role, boolean active) {
        User user = new User();
        user.setEmail(email);
        user.setRole(role);
        user.setActive(active);
        return user;
    }
}
