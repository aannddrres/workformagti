package ge.magti.portal.compliance;

import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplianceCalculatorTest {

    private static User user(Long id, String department, Role role, boolean active) {
        User user = new User();
        user.setId(id);
        user.setDepartment(department);
        user.setRole(role);
        user.setActive(active);
        return user;
    }

    @Test
    void activeOperatorIsEligible() {
        assertTrue(ComplianceCalculator.isEligible(user(1L, "ოფისი", Role.OPERATOR, true)));
    }

    @Test
    void managementRolesAreNotEligibleEvenIfActive() {
        assertFalse(ComplianceCalculator.isEligible(user(1L, "All", Role.MANAGER, true)));
        assertFalse(ComplianceCalculator.isEligible(user(2L, "All", Role.CONTENT_ADMIN, true)));
        assertFalse(ComplianceCalculator.isEligible(user(3L, "All", Role.SYSTEM_ADMIN, true)));
    }

    @Test
    void inactiveOperatorIsNotEligible() {
        assertFalse(ComplianceCalculator.isEligible(user(1L, "ოფისი", Role.OPERATOR, false)));
    }

    @Test
    void allDepartmentUserOnlyCountsAllTargetedReadings() {
        User user = user(1L, "All", Role.OPERATOR, true);
        Map<String, Integer> required = Map.of("All", 5, "ოფისი", 3);
        Map<ReadCountKey, Integer> read = Map.of(new ReadCountKey(1L, "All"), 2);

        ReadingProgress progress = ComplianceCalculator.computeProgress(user, 5, required, read);

        assertEquals(5, progress.requiredCount());
        assertEquals(2, progress.readCount());
        assertEquals(40, progress.percentage());
    }

    @Test
    void groupSuffixedUserCountsParentDepartmentReadingsToo() {
        // The exact scenario the Python-side WIP fixed earlier this session:
        // a reading targeted at the parent department must count for a
        // group-suffixed user, not just an exact department-string match.
        User user = user(1L, "ტექნიკური — ჯგუფი 03", Role.OPERATOR, true);
        Map<String, Integer> required = Map.of("All", 1, "ტექნიკური", 4);
        Map<ReadCountKey, Integer> read = Map.of(
                new ReadCountKey(1L, "All"), 1,
                new ReadCountKey(1L, "ტექნიკური"), 3);

        ReadingProgress progress = ComplianceCalculator.computeProgress(user, 1, required, read);

        assertEquals(5, progress.requiredCount());
        assertEquals(4, progress.readCount());
    }

    @Test
    void departmentWithNoGroupSuffixDoesNotDoubleCount() {
        // department == its own split-prefix here -- must not throw (Set.of
        // would) and must not double-count the same bucket twice.
        User user = user(1L, "ოფისი", Role.OPERATOR, true);
        Map<String, Integer> required = Map.of("All", 1, "ოფისი", 4);
        Map<ReadCountKey, Integer> read = Map.of(new ReadCountKey(1L, "ოფისი"), 4);

        ReadingProgress progress = ComplianceCalculator.computeProgress(user, 1, required, read);

        assertEquals(5, progress.requiredCount());
        assertEquals(4, progress.readCount());
    }

    @Test
    void zeroRequiredReturnsAllZeros() {
        User user = user(1L, "ოფისი", Role.OPERATOR, true);

        ReadingProgress progress = ComplianceCalculator.computeProgress(user, 0, Map.of(), Map.of());

        assertEquals(0, progress.requiredCount());
        assertEquals(0, progress.readCount());
        assertEquals(0, progress.percentage());
    }

    @Test
    void exactTieRoundsDownToTheNearestEvenPercentage() {
        // 1/8 = 12.5% exactly -- Python's round(12.5) == 12 (round-half-to-even).
        User user = user(1L, "All", Role.OPERATOR, true);
        Map<ReadCountKey, Integer> read = Map.of(new ReadCountKey(1L, "All"), 1);

        ReadingProgress progress = ComplianceCalculator.computeProgress(user, 8, Map.of(), read);

        assertEquals(12, progress.percentage());
    }

    @Test
    void exactTieRoundsUpToTheNearestEvenPercentage() {
        // 3/8 = 37.5% exactly -- Python's round(37.5) == 38 (round-half-to-even).
        User user = user(1L, "All", Role.OPERATOR, true);
        Map<ReadCountKey, Integer> read = Map.of(new ReadCountKey(1L, "All"), 3);

        ReadingProgress progress = ComplianceCalculator.computeProgress(user, 8, Map.of(), read);

        assertEquals(38, progress.percentage());
    }
}
