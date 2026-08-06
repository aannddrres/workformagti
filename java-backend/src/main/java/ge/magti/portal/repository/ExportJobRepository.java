package ge.magti.portal.repository;

import ge.magti.portal.domain.ExportJob;
import org.springframework.data.jpa.repository.JpaRepository;

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
    List<ExportJob> findByExpiresAtLessThan(double now);
}
