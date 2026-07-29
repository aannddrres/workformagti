package ge.magti.portal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoleTest {

    @Test
    void systemAdminWireValueIsBareAdminNotSystemAdmin() {
        assertEquals("admin", Role.SYSTEM_ADMIN.value());
        assertEquals(Role.SYSTEM_ADMIN, Role.fromValue("admin"));
    }

    @Test
    void everyRoleRoundTripsThroughItsValue() {
        for (Role role : Role.values()) {
            assertEquals(role, Role.fromValue(role.value()));
        }
    }

    @Test
    void theIntuitiveButWrongGuessIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Role.fromValue("system_admin"));
    }
}
