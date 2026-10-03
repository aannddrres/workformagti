package ge.magti.portal.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseEventVisibilityTest {

    @Test
    void adminSeesEverythingRegardlessOfMismatch() {
        assertTrue(SseEventVisibility.isVisible(
                true, "Other", "operator", 999L,
                "Mine", "manager", 1L));
    }

    @Test
    void allDepartmentAndAllRoleReachesEveryNonAdminViewer() {
        assertTrue(SseEventVisibility.isVisible(
                false, "All", "All", null,
                "ნებისმიერი დეპარტამენტი", "operator", 1L));
    }

    @Test
    void exactDepartmentAndRoleMatch() {
        assertTrue(SseEventVisibility.isVisible(
                false, "გაყიდვები", "operator", null,
                "გაყიდვები", "operator", 1L));
    }

    @Test
    void subGroupDepartmentNowMatchesParentTarget() {
        // The fix: the original used exact string equality, so a
        // sub-group viewer never got the live pop-up despite already seeing
        // the same content via the prefix-aware visibility filter elsewhere.
        assertTrue(SseEventVisibility.isVisible(
                false, "გაყიდვები", "All", null,
                "გაყიდვები — ჯგუფი 2", "operator", 1L));
    }

    @Test
    void differentParentDepartmentDoesNotMatch() {
        assertFalse(SseEventVisibility.isVisible(
                false, "გაყიდვები", "All", null,
                "ტექნიკური — ჯგუფი 1", "operator", 1L));
    }

    @Test
    void roleMismatchBlocksEvenWithDepartmentMatch() {
        assertFalse(SseEventVisibility.isVisible(
                false, "All", "manager", null,
                "გაყიდვები", "operator", 1L));
    }

    @Test
    void targetUserIdRestrictsToThatSingleViewer() {
        assertTrue(SseEventVisibility.isVisible(
                false, "All", "All", 42L,
                "All", "operator", 42L));
        assertFalse(SseEventVisibility.isVisible(
                false, "All", "All", 42L,
                "All", "operator", 7L));
    }

    @Test
    void nullTargetDepartmentAndRoleTreatedAsUnrestricted() {
        assertTrue(SseEventVisibility.isVisible(
                false, null, null, null,
                "ნებისმიერი", "operator", 1L));
    }
}
