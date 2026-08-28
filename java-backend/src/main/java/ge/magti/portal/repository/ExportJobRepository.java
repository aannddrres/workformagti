package ge.magti.portal.repository;

import ge.magti.portal.domain.ExportJob;
import ge.magti.portal.export.ExpiredExportJobReference;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ExportJobRepository extends JpaRepository<ExportJob, String> {

    /**
     * Backs {@link ge.magti.portal.export.ExportJobCleanupScheduler} -- the
     * fix for known bug #9 (ExportJob.java's javadoc): Python writes {@code
     * expires_at} on every job (at creation and again on completion) but
     * never once reads it back, so finished export files sit on disk
     * forever. {@code expiresAt} is set from creation, so this also sweeps
     * up a job whose worker crashed mid-build, not just completed/failed ones.
     */
    @Query("SELECT new ge.magti.portal.export.ExpiredExportJobReference(j.id, j.path) "
            + "FROM ExportJob j WHERE j.expiresAt < :now ORDER BY j.id")
    List<ExpiredExportJobReference> findExpiredReferences(
            @Param("now") double now,
            Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM ExportJob j WHERE j.id IN :ids")
    int deleteExpiredByIds(@Param("ids") List<String> ids);
}
