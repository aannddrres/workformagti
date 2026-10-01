package ge.magti.portal.stats;

import ge.magti.portal.compliance.ReadingProgress;
import ge.magti.portal.domain.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorStatsBuilderTest {

    private static ComplianceRecord record(Long id, String name, String department, int required, int read, int percentage) {
        return record(id, name, department, required, read, percentage, 0);
    }

    private static ComplianceRecord record(
            Long id, String name, String department, int required, int read, int percentage, int overdue) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setDepartment(department);
        return new ComplianceRecord(user, new ReadingProgress(required, read, percentage, overdue));
    }

    @Test
    void criticalOperatorsExcludesZeroRequiredAndAboveThresholdMembers() {
        List<ComplianceRecord> records = List.of(
                record(1L, "Nika Agdgomelashvili", "ოფისი", 10, 2, 20, 3), // critical: below 30
                record(2L, "Ana Beridze", "ოფისი", 10, 9, 90),          // fine: 90 >= 30
                record(3L, "Giorgi Kldiashvili", "ოფისი", 0, 0, 0));    // excluded: nothing required

        List<CriticalOperator> critical = OperatorStatsBuilder.buildCriticalOperators(records);

        assertEquals(1, critical.size());
        assertEquals(1L, critical.get(0).userId());
        assertEquals("Nika", critical.get(0).firstName());
        assertEquals("Agdgomelashvili", critical.get(0).lastName());
        assertEquals(3, critical.get(0).overdueCount(), "past its deadline -- not 10 - 2 unread");
    }

    /** Simulation, 2026-10-01: due tomorrow is not overdue, and 90% with one item late is. */
    @Test
    void overdueMeansPastTheDeadlineNotMerelyUnread() {
        List<CriticalOperator> critical = OperatorStatsBuilder.buildCriticalOperators(List.of(
                record(1L, "A", "ოფისი", 2, 0, 0, 0),     // nothing read, nothing due yet
                record(2L, "B", "ოფისი", 10, 9, 90, 1))); // fine overall, one item late

        assertEquals(List.of(2L, 1L), critical.stream().map(CriticalOperator::userId).toList());
        assertEquals(0, critical.get(1).overdueCount());
        assertEquals(1, critical.get(0).overdueCount());
    }

    @Test
    void criticalOperatorsSortsByMostOverdueFirst() {
        List<ComplianceRecord> records = List.of(
                record(1L, "A", "ოფისი", 10, 8, 20, 2),  // overdue 2
                record(2L, "B", "ოფისი", 10, 1, 10, 9)); // overdue 9

        List<CriticalOperator> critical = OperatorStatsBuilder.buildCriticalOperators(records);

        assertEquals(2L, critical.get(0).userId());
        assertEquals(1L, critical.get(1).userId());
    }

    @Test
    void filterUsersInGroupMatchesOnlyTheExactBucketAndGroup() {
        User inGroup = new User();
        inGroup.setId(1L);
        inGroup.setDepartment("ტექნიკური — ჯგუფი 01");

        User otherGroupSameDept = new User();
        otherGroupSameDept.setId(2L);
        otherGroupSameDept.setDepartment("ტექნიკური — ჯგუფი 02");

        User unrecognizedDept = new User();
        unrecognizedDept.setId(3L);
        unrecognizedDept.setDepartment("სხვა უცნობი");

        List<User> matched = OperatorStatsBuilder.filterUsersInGroup(
                List.of(inGroup, otherGroupSameDept, unrecognizedDept), "ტექნიკური", "ჯგუფი 01");

        assertEquals(1, matched.size());
        assertEquals(1L, matched.get(0).getId());
    }

    @Test
    void groupUserCompletionsSortAscendingByPercentage() {
        List<ComplianceRecord> records = List.of(
                record(1L, "A", "ოფისი", 10, 9, 90),
                record(2L, "B", "ოფისი", 10, 1, 10));

        List<GroupMemberCompletion> result = OperatorStatsBuilder.buildGroupUserCompletions(records);

        assertEquals(2L, result.get(0).userId()); // lowest percentage first
        assertEquals(1L, result.get(1).userId());
        assertTrue(result.get(0).completionPercentage() < result.get(1).completionPercentage());
    }
}
