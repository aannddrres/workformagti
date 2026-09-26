package ge.magti.portal.export;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.ExportJob;
import ge.magti.portal.repository.ExportJobRepository;
import ge.magti.portal.repository.AuditLogRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresOracle
@SpringBootTest
class ExportJobRecoveryIntegrationTest {

    @Autowired private ExportJobRepository jobs;
    @Autowired private ExportJobCleanupScheduler cleanup;
    @Autowired private ExportJobRecoveryScheduler recovery;
    @Autowired private ExportJobLifecycle lifecycle;
    @Autowired private ExportJobLeaseOwner owner;
    @Autowired private AuditLogRepository audits;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @Test
    void anExpiredProcessingJobBecomesVisibleAsFailedInsteadOfDisappearing() {
        ExportJob job = new ExportJob();
        job.setId(UUID.randomUUID().toString());
        job.setStatus("processing");
        job.setExpiresAt(System.currentTimeMillis() / 1000.0 - 60);
        jobs.saveAndFlush(job);

        recovery.recover();
        cleanup.sweepExpiredJobs();

        assertEquals("failed", jobs.findById(job.getId()).orElseThrow().getStatus());
    }

    @Test
    void anotherReplicasLiveJobIsUntouchedEvenAfterItsOldFileExpiry() {
        ExportJob job = new ExportJob();
        job.setId(UUID.randomUUID().toString());
        job.setStatus("processing");
        job.setExpiresAt(jobs.databaseNow().toEpochSecond() - 60);
        job.setWorkerInstanceId(UUID.randomUUID().toString());
        job.setLeaseUntil(jobs.databaseNow().plusSeconds(90));
        jobs.saveAndFlush(job);

        recovery.recover();
        cleanup.sweepExpiredJobs();

        assertFalse(lifecycle.complete(job.getId(), new byte[]{9}, "wrong-replica.xlsx", "xlsx"));
        assertEquals("processing", jobs.findById(job.getId()).orElseThrow().getStatus());
    }

    @Test
    void expiredLeaseIsFailedOnceAndLateWorkerCannotPublishBytes() {
        ExportJob job = new ExportJob();
        job.setId(UUID.randomUUID().toString());
        job.setStatus("processing");
        job.setExpiresAt(jobs.databaseNow().toEpochSecond() + 3600);
        job.setWorkerInstanceId(owner.id());
        job.setLeaseUntil(jobs.databaseNow().minusSeconds(1));
        jobs.saveAndFlush(job);

        recovery.recover();
        recovery.recover();

        assertFalse(lifecycle.complete(job.getId(), new byte[]{1, 2}, "late.xlsx", "xlsx"));
        ExportJob persisted = jobs.findById(job.getId()).orElseThrow();
        assertEquals("failed", persisted.getStatus());
        assertNull(persisted.getContent());
        assertTrue(persisted.getExpiresAt() > jobs.databaseNow().toEpochSecond() + 3500);
        assertEquals(1, audits.findAll().stream()
                .filter(row -> "EXPORT_JOB_INTERRUPTED".equals(row.getAction()))
                .filter(row -> row.getDetails().contains(job.getId())).count());
    }

    /**
     * The lease is compared with SYSTIMESTAMP in SQL, so what JPA writes has
     * to be that same instant whatever zone the JVM and the session run in.
     * The session is moved off both UTC and +04:00 so this holds on any
     * workstation. Through the Tbilisi converter the lease landed five hours
     * early under +09:00, and four hours late on a UTC JVM -- CI's, and the
     * backend image's.
     */
    @Test
    @Transactional
    void aLeaseWrittenThroughJpaIsTheInstantSqlComparesItWith() {
        String originalZone = jdbc.queryForObject("SELECT SESSIONTIMEZONE FROM dual", String.class);
        jdbc.execute("ALTER SESSION SET TIME_ZONE = '+09:00'");
        try {
            ExportJob job = new ExportJob();
            job.setId(UUID.randomUUID().toString());
            job.setStatus("processing");
            job.setExpiresAt(jobs.databaseNow().toEpochSecond() + 3600);
            job.setWorkerInstanceId(owner.id());
            job.setLeaseUntil(jobs.databaseNow().plusSeconds(90));
            jobs.saveAndFlush(job);
            entityManager.clear();

            Long secondsAhead = jdbc.queryForObject(
                    "SELECT ROUND((CAST(SYS_EXTRACT_UTC(lease_until) AS DATE) "
                            + "- CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS DATE)) * 86400) "
                            + "FROM export_jobs WHERE id = ?", Long.class, job.getId());
            assertTrue(secondsAhead != null && secondsAhead >= 60 && secondsAhead <= 91,
                    "the lease is " + secondsAhead + " s from SYSTIMESTAMP; 90 were written");
            assertTrue(jobs.findById(job.getId()).orElseThrow().getLeaseUntil().isAfter(jobs.databaseNow()),
                    "read back, the lease must still be in the future");
        } finally {
            jdbc.execute("ALTER SESSION SET TIME_ZONE = '" + originalZone + "'");
        }
    }

    @Test
    void currentOwnerCompletesWithItsLeaseAndPublishesDownloadableBytes() {
        ExportJob job = new ExportJob();
        job.setId(UUID.randomUUID().toString());
        job.setStatus("processing");
        job.setExpiresAt(jobs.databaseNow().toEpochSecond() + 3600);
        job.setWorkerInstanceId(owner.id());
        job.setLeaseUntil(jobs.databaseNow().plusSeconds(90));
        jobs.saveAndFlush(job);

        assertTrue(lifecycle.complete(job.getId(), new byte[]{1, 2}, "ready.xlsx", "xlsx"));
        ExportJob persisted = jobs.findById(job.getId()).orElseThrow();
        assertEquals("completed", persisted.getStatus());
        assertEquals(2, persisted.getContent().length);
        assertEquals("ready.xlsx", persisted.getFilename());
        assertNull(persisted.getWorkerInstanceId());
        assertNull(persisted.getLeaseUntil());
    }
}
