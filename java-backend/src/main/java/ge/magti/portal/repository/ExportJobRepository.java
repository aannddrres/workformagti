package ge.magti.portal.repository;

import ge.magti.portal.domain.ExportJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

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

    /**
     * The ownership lookup behind {@code GET /api/export/status/{jobId}} and
     * {@code /download/{jobId}} (DEC-P03).
     *
     * <p>Scoping the query rather than fetching by id and comparing
     * afterwards is deliberate: another caller's job comes back empty, so it
     * is indistinguishable from an id that does not exist, and the endpoints
     * answer it with the response they already give for an unknown id. A
     * distinct "not yours" would confirm the job exists to somebody who may
     * not know it does.
     *
     * <p>A row with a NULL {@code created_by} -- written before {@code V36}
     * -- never matches, which is the intended answer: nobody owns it.
     */
    Optional<ExportJob> findByIdAndCreatedBy(String id, Long createdBy);
}
