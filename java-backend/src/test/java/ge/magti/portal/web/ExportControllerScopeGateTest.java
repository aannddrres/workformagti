package ge.magti.portal.web;

import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.export.ExportJobWorker;
import ge.magti.portal.export.ExportQueryService;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.ExportJobRepository;
import ge.magti.portal.security.PermissionChecker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB-free proof that {@code reports.export} grants the export <i>action</i>
 * and never a scope over other employees.
 *
 * <p>A system admin may grant this permission to anyone. Before Phase 0 that
 * grant produced an org-wide export of every employee's name, department and
 * per-item compliance status; scoping the query narrowed that to the holder's
 * own department, which is the same fail-open one step smaller -- a content
 * admin's department string says where they sit, not anybody they lead
 * (ORG_ACCESS_ARCHITECTURE_PLAN_KA.md §8). The controller now asks the second
 * question too, so a grant alone produces nothing readable.
 *
 * <p>Deliberately asserted at the controller rather than only in
 * {@code ExportQueryServiceScopingTest}: the service keeps pinning every
 * non-admin caller to a department as defence in depth, so a service-level
 * test cannot tell "the gate is there" from "the gate was removed and the
 * inner layer caught it".
 */
class ExportControllerScopeGateTest {

    private final ExportQueryService exportQueryService = mock(ExportQueryService.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final ExportController controller = new ExportController(
            exportQueryService,
            mock(ExportJobRepository.class),
            mock(ExportJobWorker.class),
            auditLogRepository,
            new PermissionChecker());

    ExportControllerScopeGateTest() {
        when(exportQueryService.eligibleReadingRows(any())).thenReturn(List.of());
        when(exportQueryService.departmentComplianceTotals(any())).thenReturn(new TreeMap<>());
    }

    private static User holderOf(Role role) {
        User user = new User();
        user.setId(7L);
        user.setRole(role);
        user.setDepartment("ტექნიკური — ჯგუფი 03");
        user.setPermissions(Set.of(Permission.REPORTS_EXPORT.value()));
        return user;
    }

    @Test
    void aGrantWithoutLeadershipIsRefusedOnEveryEmployeeDataExport() {
        for (Role role : List.of(Role.CONTENT_ADMIN, Role.OPERATOR)) {
            User holder = holderOf(role);

            assertEquals(HttpStatus.FORBIDDEN, controller.exportReadingsCsv(holder).getStatusCode(),
                    role + " holds reports.export but leads nobody");
            assertEquals(HttpStatus.FORBIDDEN, controller.exportReadingsXlsx(holder).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, controller.exportReadingsPdf(holder).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, controller.exportTeamStatsPdf(holder).getStatusCode());
        }
    }

    /**
     * The job endpoints share the gate rather than staying on the permission
     * alone -- otherwise the weaker of the two rules would be the reachable
     * one, and a refused caller could still read a job another caller built.
     */
    @Test
    void theJobStatusAndDownloadEndpointsShareTheSameGate() {
        User holder = holderOf(Role.CONTENT_ADMIN);

        assertEquals(HttpStatus.FORBIDDEN, controller.getExportStatus("job-1", holder).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.downloadExport("job-1", holder).getStatusCode());
    }

    /** A refusal must not leave an EXPORT row claiming the export happened. */
    @Test
    void aRefusedExportWritesNoAuditRow() {
        controller.exportReadingsCsv(holderOf(Role.CONTENT_ADMIN));

        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void managerAndSystemAdminKeepTheirExports() {
        for (Role role : List.of(Role.MANAGER, Role.SYSTEM_ADMIN)) {
            assertEquals(HttpStatus.OK, controller.exportReadingsCsv(holderOf(role)).getStatusCode(),
                    role + " must keep the export it already had");
        }
    }
}
