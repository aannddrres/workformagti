package ge.magti.portal.messaging;

import ge.magti.portal.domain.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectMessagePermissionTest {

    @Test
    void systemAdminCanMessageAnyDepartment() {
        assertTrue(DirectMessagePermission.canSend(Role.SYSTEM_ADMIN, "Anything", "Completely different"));
    }

    @Test
    void contentAdminIsNotRestrictedByThisCheck() {
        // routers/messaging.py:215 only branches on ROLE_MANAGER -- any other
        // role that reaches this endpoint (whatever the route dependency
        // allows) is unrestricted by this specific check.
        assertTrue(DirectMessagePermission.canSend(Role.CONTENT_ADMIN, "გაყიდვები", "ტექნიკური"));
    }

    @Test
    void managerCanMessageExactSameDepartment() {
        assertTrue(DirectMessagePermission.canSend(Role.MANAGER, "გაყიდვები", "გაყიდვები"));
    }

    @Test
    void managerCanMessageOwnSubGroup() {
        // The fix: routers/messaging.py:217 used exact string inequality, so
        // a parent-department manager could never message an operator in
        // one of their own sub-groups.
        assertTrue(DirectMessagePermission.canSend(Role.MANAGER, "გაყიდვები", "გაყიდვები — ჯგუფი 2"));
    }

    @Test
    void managerCannotMessageDifferentParentDepartment() {
        assertFalse(DirectMessagePermission.canSend(Role.MANAGER, "გაყიდვები", "ტექნიკური"));
    }

    @Test
    void subGroupManagerCannotMessageBareParentDepartment() {
        // Deliberately asymmetric: a sub-group manager does not gain the
        // parent's full reach, or a sibling sub-group's.
        assertFalse(DirectMessagePermission.canSend(Role.MANAGER, "გაყიდვები — ჯგუფი 2", "გაყიდვები"));
    }

    @Test
    void subGroupManagerCannotMessageSiblingSubGroup() {
        assertFalse(DirectMessagePermission.canSend(Role.MANAGER, "გაყიდვები — ჯგუფი 1", "გაყიდვები — ჯგუფი 2"));
    }
}
