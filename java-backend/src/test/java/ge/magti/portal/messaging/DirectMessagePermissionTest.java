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

    /**
     * An unassigned manager used to be mapped to the target "All", which
     * DepartmentMatcher reads as a wildcard -- so the one account whose
     * scope was least defined could message the whole company.
     * {@code users.department} is nullable, so this is reachable.
     */
    @Test
    void managerWithNoDepartmentCanMessageNobody() {
        assertFalse(DirectMessagePermission.canSend(Role.MANAGER, null, "გაყიდვები"));
        assertFalse(DirectMessagePermission.canSend(Role.MANAGER, "   ", "გაყიდვები"));
    }

    /**
     * A department stored literally as "All" is NOT the same case: it is a
     * value someone chose rather than one nobody filled in, and it is a
     * wildcard target everywhere else in this codebase. Pinned because the
     * fix above briefly lumped the two together and broke this.
     */
    @Test
    void managerStoredAsAllKeepsItsExistingReach() {
        assertTrue(DirectMessagePermission.canSend(Role.MANAGER, "All", "All"));
        assertTrue(DirectMessagePermission.canSend(Role.MANAGER, "All", "გაყიდვები"));
    }
}
