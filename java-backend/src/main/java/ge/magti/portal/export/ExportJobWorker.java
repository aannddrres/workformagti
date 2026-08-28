package ge.magti.portal.export;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.ExportJob;
import ge.magti.portal.repository.ExportJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors {@code _async_file_worker} (routers/exports.py:347-379): compiles
 * an export file from already-fetched, primitive row data (no DB querying
 * here -- that already happened synchronously in the controller, same as
 * Python guarding export size before enqueueing) and records the outcome
 * on the {@link ExportJob} row. FastAPI's {@code BackgroundTasks} maps to
 * Spring's {@code @Async} -- see {@code PortalBackendApplication}'s {@code
 * @EnableAsync}. Must be called through the Spring proxy (i.e. injected as
 * a bean, not invoked as a same-class method) for {@code @Async} to apply.
 *
 * <p><b>Deliberate divergence from Python (audit PR-03/BL-09):</b> the built
 * file goes into the {@code export_jobs} row itself, not into
 * {@code <uploads-dir>/exports}. It used to be written to the container's own
 * filesystem while the row it belongs to lived in shared Oracle, so a
 * download load-balanced to any other replica read nothing and returned a
 * misleading "not ready yet" 404. Bytes and status now move together, which
 * also means they cannot get out of step. See
 * {@code ge.magti.portal.storage.FileStorageService} for why the database and
 * not a shared volume.
 */
@Service
public class ExportJobWorker {

    private static final Logger log = LoggerFactory.getLogger(ExportJobWorker.class);
    private static final String WORKER_ACTOR = "EXPORT_WORKER";

    /** Mirrors {@code _EXPORT_JOB_TTL} (routers/exports.py:30) -- also used
     *  by {@link ge.magti.portal.web.ExportController} when it creates the
     *  initial "processing" row, same as Python's {@code _enqueue_export}. */
    public static final long EXPORT_JOB_TTL_SECONDS = 3600;

    private final ExportJobRepository exportJobRepository;
    private final MutationAuditService mutationAuditService;

    public ExportJobWorker(
            ExportJobRepository exportJobRepository,
            MutationAuditService mutationAuditService) {
        this.exportJobRepository = exportJobRepository;
        this.mutationAuditService = mutationAuditService;
    }

    @Async
    @Transactional
    public void buildAndStore(String jobId, String title, List<String> headers, List<List<Object>> rows, String exportType) {
        buildAndStore(jobId, title, headers, rows, exportType, "export");
    }

    /** Dedicated filename prefix for classified SYSTEM_ADMIN data exports. */
    @Async
    @Transactional
    public void buildAdminAndStore(
            String jobId, String title, List<String> headers, List<List<Object>> rows, String filenamePrefix) {
        buildAndStore(jobId, title, headers, rows, "xlsx", filenamePrefix);
    }

    private void buildAndStore(
            String jobId, String title, List<String> headers, List<List<Object>> rows,
            String exportType, String filenamePrefix) {
        ExportJob job = exportJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        Map<String, Object> before = snapshot(job, exportType);

        byte[] data;
        try {
            data = "xlsx".equals(exportType)
                    ? XlsxExportBuilder.build(title, headers, rows)
                    : PdfExportBuilder.build(title, headers, rows);
        } catch (RuntimeException e) {
            log.warn("export worker failed (job {}): {}", jobId, e.getMessage());
            markFailed(job, exportType, before);
            return;
        }

        job.setStatus("completed");
        job.setContent(data);
        job.setFilename(filenamePrefix + "_" + jobId + "." + exportType);
        job.setPath(null);
        job.setExpiresAt(nowEpochSeconds() + EXPORT_JOB_TTL_SECONDS);
        exportJobRepository.saveAndFlush(job);
        mutationAuditService.recordSystemResult(
                WORKER_ACTOR, "EXPORT_JOB_COMPLETED", "export_job", 0L,
                "Export job", "SUCCESS", null, before, snapshot(job, exportType));
    }

    private void markFailed(ExportJob job, String exportType, Map<String, Object> before) {
        job.setStatus("failed");
        job.setPath(null);
        job.setExpiresAt(nowEpochSeconds() + EXPORT_JOB_TTL_SECONDS);
        exportJobRepository.saveAndFlush(job);
        mutationAuditService.recordSystemResult(
                WORKER_ACTOR, "EXPORT_JOB_FAILED", "export_job", 0L,
                "Export job", "FAILURE", "EXPORT_BUILD_FAILED",
                before, snapshot(job, exportType));
    }

    private static Map<String, Object> snapshot(ExportJob job, String exportType) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("job_id", job.getId());
        snapshot.put("owner_user_id", job.getOwnerUserId());
        snapshot.put("export_family", job.getExportFamily());
        snapshot.put("export_format", exportType);
        snapshot.put("status", job.getStatus());
        snapshot.put("byte_size", job.getContent() == null ? 0 : job.getContent().length);
        return snapshot;
    }

    private static double nowEpochSeconds() {
        return System.currentTimeMillis() / 1000.0;
    }
}
