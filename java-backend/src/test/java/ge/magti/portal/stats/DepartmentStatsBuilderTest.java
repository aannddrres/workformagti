package ge.magti.portal.stats;

import ge.magti.portal.compliance.ReadingProgress;
import ge.magti.portal.domain.User;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DepartmentStatsBuilderTest {

    private static ComplianceRecord record(Long id, String department, int required, int read, int percentage) {
        User user = new User();
        user.setId(id);
        user.setName("user-" + id);
        user.setDepartment(department);
        return new ComplianceRecord(user, new ReadingProgress(required, read, percentage));
    }

    @Test
    void buildsTheDepartmentGroupMemberTree() {
        List<ComplianceRecord> records = List.of(
                record(1L, "ტექნიკური — ჯგუფი 01", 10, 10, 100),
                record(2L, "ტექნიკური — ჯგუფი 01", 10, 2, 20),
                record(3L, "ტექნიკური — ჯგუფი 02", 4, 4, 100),
                record(4L, "საინფორმაციო", 0, 0, 0),
                record(5L, "სხვა უცნობი განყოფილება", 999, 999, 100));

        DepartmentDashboard dashboard = DepartmentStatsBuilder.build(records, OffsetDateTime.now());
        Map<String, DepartmentStats> byName = dashboard.departments().stream()
                .collect(java.util.stream.Collectors.toMap(DepartmentStats::name, d -> d));

        // Unrecognized department (user 5) must not appear anywhere.
        assertEquals(3, dashboard.departments().size());
        assertEquals(4, dashboard.insights().totalMembers());

        DepartmentStats technical = byName.get("ტექნიკური");
        assertEquals(3, technical.memberCount());
        assertEquals(2, technical.groupCount());
        assertEquals(1, technical.criticalCount()); // user 2, 20% < 30
        assertEquals(16, technical.outputVolume()); // 10 + 2 + 4
        assertEquals(73, technical.compliance()); // (100+20+100)/3 = 73.33 -> 73
        assertEquals("ჯგუფი 01", technical.groups().get(0).name());
        assertEquals("ჯგუფი 02", technical.groups().get(1).name());
        // Sorted by percentage descending within the group.
        assertEquals(1L, technical.groups().get(0).members().get(0).userId());
        assertEquals(2L, technical.groups().get(0).members().get(1).userId());
        assertEquals("ტექნიკური — ჯგუფი 01", technical.groups().get(0).fullDepartment());

        DepartmentStats informational = byName.get("საინფორმაციო");
        assertEquals(1, informational.memberCount());
        assertEquals(0, informational.outputVolume());
        assertEquals(0, informational.criticalCount());
        assertEquals(0, informational.compliance()); // zero-required member excluded from the average, not counted as 0%
        // Bare department with no group suffix -> group named after the department itself.
        assertEquals("საინფორმაციო", informational.groups().get(0).fullDepartment());

        DepartmentStats office = byName.get("ოფისი");
        assertTrue(office.empty());
        assertEquals(0, office.memberCount());
    }

    @Test
    void averageRoundsHalfToEvenNotHalfUp() {
        // (25 + 50) / 2 = 37.5 exactly -- rounds half-to-even to 38.
        List<ComplianceRecord> records = List.of(
                record(1L, "ოფისი", 4, 1, 25),
                record(2L, "ოფისი", 4, 2, 50));

        DepartmentDashboard dashboard = DepartmentStatsBuilder.build(records, OffsetDateTime.now());

        assertEquals(38, dashboard.insights().globalCompliance());
    }

    /** Mutation testing, 2026-10-02: the input above is already sorted, so dropping either sort went unnoticed. */
    @Test
    void groupsAreOrderedByNameAndMembersByCompletionWhateverTheInputOrder() {
        List<ComplianceRecord> records = List.of(
                record(1L, "ტექნიკური — ჯგუფი 02", 10, 1, 10),
                record(2L, "ტექნიკური — ჯგუფი 01", 10, 5, 50),
                record(3L, "ტექნიკური — ჯგუფი 01", 10, 9, 90));

        DepartmentStats technical = DepartmentStatsBuilder.build(records, OffsetDateTime.now()).departments().stream()
                .filter(d -> d.name().equals("ტექნიკური")).findFirst().orElseThrow();

        assertEquals(List.of("ჯგუფი 01", "ჯგუფი 02"), technical.groups().stream().map(DepartmentGroupStats::name).toList());
        assertEquals(List.of(3L, 2L), technical.groups().get(0).members().stream().map(DepartmentMember::userId).toList());
    }

    /** Someone who owes nothing is left out of a group's average, not counted as 0 % (mutation testing, 2026-10-02). */
    @Test
    void aMemberWhoOwesNothingDoesNotPullTheGroupAverageDown() {
        List<ComplianceRecord> records = List.of(
                record(1L, "ოფისი — ჯგუფი 01", 0, 0, 0),
                record(2L, "ოფისი — ჯგუფი 01", 4, 4, 100));

        DepartmentGroupStats group = DepartmentStatsBuilder.build(records, OffsetDateTime.now()).departments().stream()
                .filter(d -> d.name().equals("ოფისი")).findFirst().orElseThrow().groups().getFirst();

        assertEquals(100, group.compliance());
    }

    /** The tile counts what the list it opens shows: below 30 % or anything overdue (RoleFlowIntegrationTest). */
    @Test
    void someoneWithAnOverdueItemIsCriticalOnTheDashboardEvenAboveThirtyPercent() {
        List<ComplianceRecord> records = List.of(new ComplianceRecord(
                record(1L, "ოფისი — ჯგუფი 01", 2, 1, 50).user(), new ReadingProgress(2, 1, 50, 1)));

        assertEquals(1, DepartmentStatsBuilder.build(records, OffsetDateTime.now()).insights().criticalOperators());
    }
}
