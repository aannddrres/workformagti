package ge.magti.portal.domain;

import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserTest {

    @Test
    void lastActiveKeepsTheTbilisiOffsetExplicit() {
        User user = new User();
        user.setLastActive(TbilisiTime.now());

        assertEquals(ZoneOffset.ofHours(4), user.getLastActive().getOffset());
    }

    @Test
    void hasPermissionChecksTheRawStringSet() {
        User user = new User();
        user.setPermissions(Set.of(Permission.ARTICLES_EDIT.value()));

        assertTrue(user.hasPermission(Permission.ARTICLES_EDIT));
        assertFalse(user.hasPermission(Permission.ARTICLES_ARCHIVE));
    }

    @Test
    void newInstanceMatchesModelsPyColumnDefaults() {
        User user = new User();

        assertEquals(Role.OPERATOR, user.getRole());
        assertTrue(user.isActive());
        assertTrue(user.getPermissions().isEmpty());
        assertEquals("corporate", user.getCardStyle());
    }
}
