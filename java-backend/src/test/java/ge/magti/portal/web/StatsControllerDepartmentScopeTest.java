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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DB-free proof that SEC-03's redaction branch is actually wired into
 * {@code GET /api/manager/department-stats}, not just available on
 * {@code DepartmentStatsBuilder}. Runs everywhere, unlike
 * {@code StatsControllerIntegrationTest}, which needs a live Oracle 19c.
 *
 * <p>The fixture deliberately spans two departments: before the fix, a
 * MANAGER received the named rows of both.
 */
class StatsControllerDepartmentScopeTest {

    private final ComplianceQueryService complianceQueryService = mock(ComplianceQueryService.class);

    private final StatsController controller = new StatsController(
            complianceQueryService,
            mock(UserRepository.class),
            mock(SearchLogRepository.class),
            mock(RequiredReadingRepository.class),
            mock(ReadStatusRepository.class),
            mock(ArticleRepository.class),
            mock(VideoInstructionRepository.class),
            mock(AuditLogRepository.class),
            mock(ArticleViewLogRepository.class));

    StatsControllerDepartmentScopeTest() {
        when(complianceQueryService.computeCompliance()).thenReturn(List.of(
                record(1L, "ოპერატორი ერთი", "ტექნიკური — ჯგუფი 03", 10, 2, 20),
                record(2L, "ოპერატორი ორი", "ტექნიკური — ჯგუფი 03", 10, 10, 100),
                record(3L, "სხვისი ოპერატორი", "ოფისი — ჯგუფი 01", 4, 4, 100)));
    }

    @Test
    void managerDashboardCarriesNoNamedMembersFromAnyDepartment() {
        DepartmentDashboard dashboard = dashboardFor(userOf(Role.MANAGER, "ტექნიკური — ჯგუფი 03"));

        List<DepartmentMember> members = allMembers(dashboard);
        assertTrue(members.isEmpty(),
                "SEC-03: a manager's dashboard must carry no per-person rows, own department included");

        // Not even their own department's -- the redaction is unconditional,
        // so there is no "which department is this row from" check to get wrong.
        assertFalse(dashboard.departments().stream().anyMatch(d -> !d.groups().isEmpty()
                        && d.groups().stream().anyMatch(g -> !g.members().isEmpty())),
                "no group in any department may carry members");
    }

    @Test
    void managerKeepsEveryAggregateTheAdminSees() {
        DepartmentDashboard managerView = dashboardFor(userOf(Role.MANAGER, "ტექნიკური — ჯგუფი 03"));
        DepartmentDashboard adminView = dashboardFor(userOf(Role.SYSTEM_ADMIN, "All"));

        assertEquals(adminView.insights(), managerView.insights());
        for (int i = 0; i < adminView.departments().size(); i++) {
            var admin = adminView.departments().get(i);
            var manager = managerView.departments().get(i);
            assertEquals(admin.name(), manager.name());
            assertEquals(admin.memberCount(), manager.memberCount());
            assertEquals(admin.groupCount(), manager.groupCount());
            assertEquals(admin.compliance(), manager.compliance());
            assertEquals(admin.outputVolume(), manager.outputVolume());
            assertEquals(admin.criticalCount(), manager.criticalCount());
            for (int g = 0; g < admin.groups().size(); g++) {
                assertEquals(admin.groups().get(g).withMembers(List.of()), manager.groups().get(g));
            }
        }
    }

    @Test
    void systemAdminDashboardIsUnchangedAndStillCarriesNamedMembers() {
        DepartmentDashboard dashboard = dashboardFor(userOf(Role.SYSTEM_ADMIN, "All"));

        List<String> names = allMembers(dashboard).stream().map(DepartmentMember::userName).sorted().toList();
        assertEquals(List.of("ოპერატორი ერთი", "ოპერატორი ორი", "სხვისი ოპერატორი"), names,
                "system_admin behaviour must be unchanged");
    }

    /**
     * The gate itself is untouched by this fix: requireManagerOrAdmin admits
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
