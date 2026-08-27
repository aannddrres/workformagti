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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEC-P03: an export job belongs to the caller who asked for it.
 *
 * <h2>What was wrong</h2>
 *
 * {@code export_jobs} had no owner column at all, so
 * {@code GET /api/export/download/{jobId}} could only authorize on the
 * {@code reports.export} permission -- while the contents of a job are
 * scoped to whoever requested it ({@link ExportQueryService} pins a MANAGER
 * to their own department). A manager of one department holding the same
 * permission could download another department's export in full, given the
 * id. UUID4 ids made that authorization by obscurity rather than open
 * enumeration, but an id reaches a log, a browser history, a shared screen
 * or the audit trail -- and SEC-02's point is that the audit row must answer
 * whose personal data left the portal, which it cannot if the downloader can
 * be somebody other than the requester.
 *
 * <h2>Why here and not only in the integration test</h2>
 *
 * {@code ExportControllerIntegrationTest} carries the end-to-end version and
 * is the one that proves the column, the JPA mapping and the query agree.
 * It is {@code @RequiresOracle}, so the DB-free CI job never runs it -- and
 * an authorization check that only a database-bound suite exercises is one
 * that can regress on a push and be seen a job later. The decision itself is
 * repository-shaped, so it is checked here against a mocked repository.
 */
class ExportJobOwnershipTest {

    private static final String JOB_ID = "0c2f5f1e-0000-4000-8000-000000000001";

    private ExportJobRepository exportJobRepository;
    private ExportController controller;

    private User owner;
    private User otherManager;

    @BeforeEach
    void setUp() {
        exportJobRepository = mock(ExportJobRepository.class);
        controller = new ExportController(
                mock(ExportQueryService.class),
                exportJobRepository,
                mock(ExportJobWorker.class),
                mock(AuditLogRepository.class),
                new PermissionChecker());

        owner = manager(7L, "ტექნიკური");
        // Same role, same reports.export permission, different department --
        // the exact caller the old code could not tell apart from the owner.
        otherManager = manager(9L, "გაყიდვები");
    }

    private static User manager(long id, String department) {
        User user = new User();
        user.setId(id);
        user.setRole(Role.MANAGER);
        user.setDepartment(department);
        user.setPermissions(Permission.defaultsFor(Role.MANAGER).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new)));
        return user;
    }

    private static ExportJob completedJob(long createdBy) {
        ExportJob job = new ExportJob();
        job.setId(JOB_ID);
        job.setCreatedBy(createdBy);
        job.setStatus("completed");
        job.setFilename("export_" + JOB_ID + ".xlsx");
        job.setContent(new byte[] {1, 2, 3});
        job.setExpiresAt(System.currentTimeMillis() / 1000.0 + 3600);
        return job;
    }

    /**
     * The database state that matters: the row <b>is</b> there, owned by
     * {@code storedOwner}, and {@code caller} is not them.
     *
     * <p>Stubbing the unscoped {@code findById} as well is the whole point.
     * Leaving it unstubbed would make it answer empty, so a controller that
     * had never been fixed -- one that still fetches by id and hands the
     * bytes over -- would pass these tests for the wrong reason. Mocking the
     * row into existence is what makes reverting the fix fail here.
     */
    private void jobExistsOwnedBy(User storedOwner, User caller) {
        ExportJob stored = completedJob(storedOwner.getId());
        when(exportJobRepository.findById(anyString())).thenReturn(Optional.of(stored));
        when(exportJobRepository.findByIdAndCreatedBy(anyString(), eq(caller.getId())))
                .thenReturn(Optional.empty());
    }

    /** A pre-V36 row: present, and owned by nobody at all. */
    private void unownedJobExists() {
        ExportJob stored = completedJob(0L);
        stored.setCreatedBy(null);
        when(exportJobRepository.findById(anyString())).thenReturn(Optional.of(stored));
        when(exportJobRepository.findByIdAndCreatedBy(anyString(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void theOwnerCanDownloadTheirOwnJob() {
        when(exportJobRepository.findByIdAndCreatedBy(JOB_ID, owner.getId()))
                .thenReturn(Optional.of(completedJob(owner.getId())));

        ResponseEntity<?> response = controller.downloadExport(JOB_ID, owner);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(3, ((byte[]) response.getBody()).length);
    }

    /**
     * The property that matters is not "the other manager is refused" -- it
     * is that they are refused <i>identically</i> to somebody who guessed an
     * id. A distinct "not yours" would confirm the job exists to a caller who
     * may only suspect it does, which is the same leak in a politer form.
     */
    @Test
    void anotherManagerGetsByteForByteTheAnswerAnUnknownIdGets() {
        jobExistsOwnedBy(owner, otherManager);

        ResponseEntity<?> refused = controller.downloadExport(JOB_ID, otherManager);
        ResponseEntity<?> neverExisted =
                controller.downloadExport("11111111-2222-4333-8444-555555555555", otherManager);

        assertEquals(HttpStatus.GONE, refused.getStatusCode());
        assertEquals(neverExisted.getStatusCode(), refused.getStatusCode());
        assertEquals(neverExisted.getBody(), refused.getBody());
    }

    /** Same rule on the status endpoint: it leaks the job's existence just as well. */
    @Test
    void statusIsScopedToTheCallerToo() {
        jobExistsOwnedBy(owner, otherManager);

        ResponseEntity<?> refused = controller.getExportStatus(JOB_ID, otherManager);
        ResponseEntity<?> neverExisted =
                controller.getExportStatus("11111111-2222-4333-8444-555555555555", otherManager);

        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode());
        assertEquals(neverExisted.getBody(), refused.getBody());
    }

    /**
     * A row written before {@code V36} has {@code created_by} NULL, so the
     * scoped query matches nobody and every caller is told to regenerate.
     * Deliberate: there is no owner to attribute, and with a one-hour TTL
     * ({@link ExportJobWorker#EXPORT_JOB_TTL_SECONDS}) the whole legacy
     * population ages out within an hour of deploying. Serving them to
     * anyone with the permission would keep the hole open for exactly that
     * window.
     */
    @Test
    void aJobFromBeforeTheOwnerColumnBelongsToNobody() {
        unownedJobExists();

        assertEquals(HttpStatus.GONE, controller.downloadExport(JOB_ID, owner).getStatusCode());
        assertEquals(HttpStatus.GONE, controller.downloadExport(JOB_ID, otherManager).getStatusCode());
    }

    /**
     * The structural half, and the reason this test is worth more than an
     * assertion on the status code: the ownership must be in the QUERY, not
     * in a comparison after an unscoped fetch. Both refuse the same caller
     * today; only one of them keeps refusing after somebody adds an early
     * return, a cache or a second read path above the check.
     */
    @Test
    void ownershipIsPartOfTheQueryNotACheckAfterAnUnscopedFetch() {
        when(exportJobRepository.findByIdAndCreatedBy(JOB_ID, owner.getId()))
                .thenReturn(Optional.of(completedJob(owner.getId())));

        controller.downloadExport(JOB_ID, owner);
        controller.getExportStatus(JOB_ID, owner);

        verify(exportJobRepository, never()).findById(any());
        verify(exportJobRepository, times(2)).findByIdAndCreatedBy(JOB_ID, owner.getId());
    }

    /**
     * No administrator exception, deliberately. SYSTEM_ADMIN is unscoped in
     * {@link ExportQueryService}, so an admin's own export is a superset of
     * anyone else's and they never need to open somebody else's job -- while
     * allowing it would break the one thing SEC-02 added, an audit row that
     * says whose personal data left and who took it.
     */
    @Test
    void notEvenASystemAdminOpensSomebodyElsesJob() {
        User admin = new User();
        admin.setId(1L);
        admin.setRole(Role.SYSTEM_ADMIN);
        admin.setPermissions(Set.of());
        jobExistsOwnedBy(owner, admin);

        assertEquals(HttpStatus.GONE, controller.downloadExport(JOB_ID, admin).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.getExportStatus(JOB_ID, admin).getStatusCode());
    }
}
