package ge.magti.portal.web;

import ge.magti.portal.compliance.ComplianceQueryService;
import ge.magti.portal.compliance.ReadingProgress;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleViewLogRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.SearchLogRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import ge.magti.portal.stats.ComplianceRecord;
import ge.magti.portal.stats.DepartmentDashboard;
import ge.magti.portal.stats.DepartmentMember;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DB-free proof that Phase 0 scopes the complete department dashboard wire
 * shape, not only its member lists. Runs everywhere, unlike
 * {@code StatsControllerIntegrationTest}, which needs a live Oracle 19c.
 *
 * <p>The fixture deliberately spans two departments: before the fix, a
 * MANAGER received aggregate rows for both even after names were redacted.
 */
class StatsControllerDepartmentScopeTest {

    private final ComplianceQueryService complianceQueryService = mock(ComplianceQueryService.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private final StatsController controller = new StatsController(
            complianceQueryService,
            userRepository,
            mock(SearchLogRepository.class),
            mock(RequiredReadingRepository.class),
            mock(ReadStatusRepository.class),
            mock(ArticleRepository.class),
            mock(VideoInstructionRepository.class),
            mock(AuditLogRepository.class),
            mock(ArticleViewLogRepository.class));

    StatsControllerDepartmentScopeTest() {
        List<ComplianceRecord> records = List.of(
                record(1L, "ოპერატორი ერთი", "ტექნიკური — ჯგუფი 03", 10, 2, 20),
                record(2L, "ოპერატორი ორი", "ტექნიკური — ჯგუფი 03", 10, 10, 100),
                record(3L, "სხვისი ოპერატორი", "ოფისი — ჯგუფი 01", 4, 4, 100));
        when(complianceQueryService.computeCompliance()).thenReturn(records);
        when(complianceQueryService.computeCompliance(anyList(), isNull())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return records.stream().filter(record -> ids.contains(record.user().getId())).toList();
        });
        when(userRepository.findByActiveTrue()).thenReturn(records.stream().map(ComplianceRecord::user).toList());
    }

    @Test
    void managerDashboardContainsOnlyTheirOwnGroupIncludingItsMembers() {
        DepartmentDashboard dashboard = dashboardFor(userOf(Role.MANAGER, "ტექნიკური — ჯგუფი 03"));

        assertEquals(List.of("ტექნიკური"), dashboard.departments().stream().map(d -> d.name()).toList());
        assertEquals(List.of("ჯგუფი 03"), dashboard.departments().getFirst().groups().stream()
                .map(group -> group.name()).toList());
        assertEquals(List.of("ოპერატორი ერთი", "ოპერატორი ორი"), allMembers(dashboard).stream()
                .map(DepartmentMember::userName).sorted().toList());
        assertFalse(dashboard.departments().stream().anyMatch(d -> "ოფისი".equals(d.name())),
                "a sibling department row must be absent, not merely redacted");
    }

    @Test
    void parentDepartmentManagerSeesOnlyTheirOwnSubtree() {
        DepartmentDashboard dashboard = dashboardFor(userOf(Role.MANAGER, "ტექნიკური"));

        assertEquals(List.of("ტექნიკური"), dashboard.departments().stream().map(d -> d.name()).toList());
        assertEquals(2, dashboard.insights().totalMembers());
        assertFalse(allMembers(dashboard).stream().anyMatch(member -> "სხვისი ოპერატორი".equals(member.userName())));
    }

    @Test
    void systemAdminDashboardIsUnchangedAndStillCarriesNamedMembers() {
        DepartmentDashboard dashboard = dashboardFor(userOf(Role.SYSTEM_ADMIN, "All"));

        List<String> names = allMembers(dashboard).stream().map(DepartmentMember::userName).sorted().toList();
        assertEquals(List.of("ოპერატორი ერთი", "ოპერატორი ორი", "სხვისი ოპერატორი"), names,
                "system_admin behaviour must be unchanged");
    }

    @Test
    void managerWithoutScopeGetsNoDepartmentOrGroupRows() {
        DepartmentDashboard dashboard = dashboardFor(userOf(Role.MANAGER, null));

        assertTrue(dashboard.departments().isEmpty());
        assertEquals(0, dashboard.insights().totalMembers());
    }

    /**
     * The gate remains fail-closed: requireManagerOrAdmin admits
     * MANAGER and SYSTEM_ADMIN only, so content_admin still gets a 403 here
     * rather than a redacted body.
     */
    @Test
    void theRoleGateIsUnchanged() {
        assertEquals(HttpStatus.FORBIDDEN,
                controller.getDepartmentStats(userOf(Role.CONTENT_ADMIN, "All")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                controller.getDepartmentStats(userOf(Role.OPERATOR, "ტექნიკური — ჯგუფი 03")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.getDepartmentStats(null).getStatusCode());
    }

    private DepartmentDashboard dashboardFor(User caller) {
        ResponseEntity<?> response = controller.getDepartmentStats(caller);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return (DepartmentDashboard) response.getBody();
    }

    private static List<DepartmentMember> allMembers(DepartmentDashboard dashboard) {
        return dashboard.departments().stream()
                .flatMap(d -> d.groups().stream())
                .flatMap(g -> g.members().stream())
                .toList();
    }

    private static ComplianceRecord record(
            Long id, String name, String department, int required, int read, int percentage) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setDepartment(department);
        user.setPosition("ოპერატორი");
        return new ComplianceRecord(user, new ReadingProgress(required, read, percentage));
    }

    private static User userOf(Role role, String department) {
        User user = new User();
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        return user;
    }
}
