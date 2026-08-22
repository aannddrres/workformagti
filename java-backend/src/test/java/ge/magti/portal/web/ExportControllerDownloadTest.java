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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB-free proof of the BL-09 fix in {@code downloadExport}.
 *
 * <p>Python -- and this port until now -- collapsed four unrelated situations
 * into one {@code 404 ექსპორტი ჯერ არ არის მზად}: still building, unknown id,
 * already downloaded (the row was deleted by the download itself), and a
 * request that landed on a replica without the file. Only the first is worth
 * waiting for, yet all four told the user to wait. These tests pin each case
 * to its own status, and pin the thing the audit actually cared about: a
 * second download of the same job id still works.
 *
 * <p>The real-Oracle, real-HTTP version of the double download is
 * {@code ExportControllerIntegrationTest.xlsxExportDownloadsTwiceAndKeepsItsJobRow};
 * this one runs on every push, including on a machine with no database.
 */
class ExportControllerDownloadTest {

    private static final byte[] FILE_BYTES = "an export".getBytes(StandardCharsets.UTF_8);

    private final ExportJobRepository exportJobRepository = mock(ExportJobRepository.class);
    private final ExportController controller = new ExportController(
            mock(ExportQueryService.class),
            exportJobRepository,
            mock(ExportJobWorker.class),
            mock(AuditLogRepository.class),
            new PermissionChecker());

    private static User exporter() {
        User user = new User();
        user.setRole(Role.SYSTEM_ADMIN);
        user.setPermissions(Set.of(Permission.REPORTS_EXPORT.value()));
        return user;
    }

    private static ExportJob job(String status, byte[] content, double expiresAt) {
        ExportJob job = new ExportJob();
        job.setId("job-1");
        job.setStatus(status);
        job.setContent(content);
        job.setFilename("export_job-1.xlsx");
        job.setExpiresAt(expiresAt);
        return job;
    }

    private static double inAnHour() {
        return System.currentTimeMillis() / 1000.0 + 3600;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> bodyOf(ResponseEntity<?> response) {
        return assertInstanceOf(Map.class, response.getBody());
    }

    /** The acceptance criterion: the same id downloads twice, unchanged. */
    @Test
    void aCompletedJobCanBeDownloadedMoreThanOnce() {
        when(exportJobRepository.findById("job-1"))
                .thenReturn(Optional.of(job("completed", FILE_BYTES, inAnHour())));

        ResponseEntity<?> first = controller.downloadExport("job-1", exporter());
        ResponseEntity<?> second = controller.downloadExport("job-1", exporter());

        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals(HttpStatus.OK, second.getStatusCode());
        assertArrayEquals(FILE_BYTES, (byte[]) first.getBody());
        assertArrayEquals(FILE_BYTES, (byte[]) second.getBody());
    }

    /**
     * The mechanism behind the one-shot bug: {@code cleanupExport} deleted the
     * row on the way out of a successful download. Asserted as "never deletes"
     * rather than only through the two-downloads test above, so that
     * reintroducing the delete fails here with an unambiguous message.
     */
    @Test
    void downloadingNeverDeletesTheJob() {
        when(exportJobRepository.findById("job-1"))
                .thenReturn(Optional.of(job("completed", FILE_BYTES, inAnHour())));

        controller.downloadExport("job-1", exporter());

        verify(exportJobRepository, never()).deleteById(anyString());
        verify(exportJobRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aStillBuildingJobIsAcceptedNotNotFound() {
        when(exportJobRepository.findById("job-1"))
                .thenReturn(Optional.of(job("processing", null, inAnHour())));

        ResponseEntity<?> response = controller.downloadExport("job-1", exporter());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("processing", bodyOf(response).get("status"));
    }

    @Test
    void aFailedJobSaysFailedInsteadOfNotReadyYet() {
        when(exportJobRepository.findById("job-1"))
                .thenReturn(Optional.of(job("failed", null, inAnHour())));

        ResponseEntity<?> response = controller.downloadExport("job-1", exporter());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("failed", bodyOf(response).get("status"));
    }

    @Test
    void anExpiredJobIsGoneEvenIfItsRowSurvivedTheSweep() {
        double anHourAgo = System.currentTimeMillis() / 1000.0 - 3600;
        when(exportJobRepository.findById("job-1"))
                .thenReturn(Optional.of(job("completed", FILE_BYTES, anHourAgo)));

        ResponseEntity<?> response = controller.downloadExport("job-1", exporter());

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertEquals("expired", bodyOf(response).get("status"));
    }

    @Test
    void anUnknownJobIsGone() {
        when(exportJobRepository.findById("nope")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.downloadExport("nope", exporter());

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertEquals("expired", bodyOf(response).get("status"));
    }

    /**
     * A pre-V31 row: bytes on some other pod's disk, path unreadable here.
     * This is the case that used to be silently indistinguishable from "still
     * building" on a multi-replica deployment.
     */
    @Test
    void aLegacyRowWhoseFileIsOnAnotherPodSaysExpiredNotNotReady() {
        ExportJob legacy = job("completed", null, inAnHour());
        legacy.setFilename(null);
        legacy.setPath("/app/uploads/exports/export_job-1.xlsx");
        when(exportJobRepository.findById("job-1")).thenReturn(Optional.of(legacy));

        ResponseEntity<?> response = controller.downloadExport("job-1", exporter());

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertEquals("expired", bodyOf(response).get("status"));
    }

    @Test
    void aCallerWithoutReportsExportIsStillForbidden() {
        User operator = new User();
        operator.setRole(Role.OPERATOR);
        operator.setPermissions(Set.of());

        ResponseEntity<?> response = controller.downloadExport("job-1", operator);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void classifiedAdminExportIsOwnerOnlyEvenBetweenSystemAdmins() {
        User owner = exporter();
        owner.setId(11L);
        User otherAdmin = exporter();
        otherAdmin.setId(12L);
        ExportJob classified = job("completed", FILE_BYTES, inAnHour());
        classified.setOwnerUserId(owner.getId());
        classified.setExportFamily("ADMIN_AUDIT_LEDGER");
        when(exportJobRepository.findById("job-1")).thenReturn(Optional.of(classified));

        assertEquals(HttpStatus.OK, controller.downloadExport("job-1", owner).getStatusCode());
        assertEquals(HttpStatus.GONE, controller.downloadExport("job-1", otherAdmin).getStatusCode());
        assertEquals(HttpStatus.OK, controller.getExportStatus("job-1", owner).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.getExportStatus("job-1", otherAdmin).getStatusCode());
    }
}
