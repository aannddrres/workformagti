package ge.magti.portal.repository;

import ge.magti.portal.domain.ExportJob;
import ge.magti.portal.export.ExpiredExportJobReference;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;

public interface ExportJobRepository extends JpaRepository<ExportJob, String> {

    /**
     * Backs {@link ge.magti.portal.export.ExportJobCleanupScheduler} -- the
     * fix for known bug #9 (ExportJob.java's javadoc): the original app wrote
     * {@code expires_at} on every job (at creation and again on completion)
     * but never once read it back, so finished export files sat on disk
     * forever. {@code expiresAt} is set from creation, so this also sweeps
     * up a job whose worker crashed mid-build, not just completed/failed ones.
     */
    @Query("SELECT new ge.magti.portal.export.ExpiredExportJobReference(j.id, j.path) "
            + "FROM ExportJob j WHERE j.status IN ('completed', 'failed') "
            + "AND j.expiresAt < :now ORDER BY j.id")
    List<ExpiredExportJobReference> findExpiredReferences(
            @Param("now") double now,
            Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM ExportJob j WHERE j.id IN :ids "
            + "AND j.status IN ('completed', 'failed') AND j.expiresAt < :now")
    int deleteExpiredByIds(@Param("ids") List<String> ids, @Param("now") double now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE export_jobs SET worker_instance_id = :owner, "
            + "lease_until = SYSTIMESTAMP + NUMTODSINTERVAL(90, 'SECOND') "
            + "WHERE id = :jobId AND status = 'processing'", nativeQuery = true)
    int startLease(@Param("jobId") String jobId, @Param("owner") String owner);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE export_jobs SET lease_until = SYSTIMESTAMP + NUMTODSINTERVAL(90, 'SECOND') "
            + "WHERE id = :jobId AND status = 'processing' AND worker_instance_id = :owner "
            + "AND lease_until > SYSTIMESTAMP", nativeQuery = true)
    int renewLease(@Param("jobId") String jobId, @Param("owner") String owner);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM ExportJob j WHERE j.id = :jobId")
    Optional<ExportJob> findByIdForUpdate(@Param("jobId") String jobId);

    @Query(value = "SELECT SYSTIMESTAMP FROM dual", nativeQuery = true)
    OffsetDateTime databaseNow();

    @Query(value = "SELECT id FROM export_jobs WHERE status = 'processing' "
            + "AND (lease_until <= SYSTIMESTAMP OR (lease_until IS NULL "
            + "AND expires_at <= (CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS DATE) "
            + "- DATE '1970-01-01') * 86400)) "
            + "ORDER BY id FETCH FIRST 500 ROWS ONLY", nativeQuery = true)
    List<String> findExpiredProcessingIds();
}
