package ge.magti.portal.web;

import ge.magti.portal.domain.ExportJob;
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
import java.util.Optional;
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
    private final ExportJobRepository exportJobRepository = mock(ExportJobRepository.class);
    private final ExportController controller = new ExportController(
            exportQueryService,
            exportJobRepository,
            mock(ExportJobWorker.class),
            auditLogRepository,
            new PermissionChecker());

    ExportControllerScopeGateTest() {
        when(exportQueryService.eligibleReadingRows(any())).thenReturn(List.of());
        when(exportQueryService.departmentComplianceTotals(any())).thenReturn(new TreeMap<>());
    }

    private static User holderOf(Role role) {
        return holderOf(role, 7L);
    }

    private static User holderOf(Role role, long id) {
        User user = new User();
        user.setId(id);
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

    // ---- D-3: a job belongs to whoever asked for it ----------------------

    private ExportJob jobOwnedBy(Long ownerId) {
        ExportJob job = new ExportJob();
        job.setId("job-1");
        job.setOwnerUserId(ownerId);
        job.setStatus("completed");
        job.setFilename("export_job-1.xlsx");
        job.setContent("bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        job.setExpiresAt(System.currentTimeMillis() / 1000.0 + 3600);
        when(exportJobRepository.findById("job-1")).thenReturn(Optional.of(job));
        return job;
    }

    /**
     * The case Phase 0's role gate left open: both of these callers may export,
     * so the permission check passes for both, and only ownership separates
     * them.
     */
    @Test
    void aManagerCannotTakeAnotherCallersExport() {
        jobOwnedBy(999L);
        User manager = holderOf(Role.MANAGER, 7L);

        assertEquals(HttpStatus.GONE, controller.downloadExport("job-1", manager).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.getExportStatus("job-1", manager).getStatusCode());
    }

    @Test
    void aManagerCanStillTakeTheirOwn() {
        jobOwnedBy(7L);
        User manager = holderOf(Role.MANAGER, 7L);

        assertEquals(HttpStatus.OK, controller.downloadExport("job-1", manager).getStatusCode());
        assertEquals(HttpStatus.OK, controller.getExportStatus("job-1", manager).getStatusCode());
    }

    /**
     * Rows built before V36 carry no owner. "We do not know whose this is"
     * must not read as "therefore yours" -- otherwise the pre-migration
     * backlog would be exactly the set of files anyone could take.
     */
    @Test
    void aJobWithNoRecordedOwnerIsNobodysExceptTheAdmins() {
        jobOwnedBy(null);

        assertEquals(HttpStatus.GONE,
                controller.downloadExport("job-1", holderOf(Role.MANAGER, 7L)).getStatusCode());
        assertEquals(HttpStatus.OK,
                controller.downloadExport("job-1", holderOf(Role.SYSTEM_ADMIN, 8L)).getStatusCode());
    }

    /**
     * Refusal is indistinguishable from "no such job". Answering differently
     * would turn the endpoint into an oracle for which job ids are real.
     */
    @Test
    void someoneElsesJobLooksExactlyLikeAJobThatDoesNotExist() {
        jobOwnedBy(999L);
        ResponseEntity<?> foreign = controller.downloadExport("job-1", holderOf(Role.MANAGER, 7L));

        when(exportJobRepository.findById("job-1")).thenReturn(Optional.empty());
        ResponseEntity<?> missing = controller.downloadExport("job-1", holderOf(Role.MANAGER, 7L));

        assertEquals(missing.getStatusCode(), foreign.getStatusCode());
        assertEquals(String.valueOf(missing.getBody()), String.valueOf(foreign.getBody()));
    }
}
