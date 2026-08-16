package ge.magti.portal.export;

import ge.magti.portal.domain.ExportJob;
import ge.magti.portal.repository.ExportJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

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

    /** Mirrors {@code _EXPORT_JOB_TTL} (routers/exports.py:30) -- also used
     *  by {@link ge.magti.portal.web.ExportController} when it creates the
     *  initial "processing" row, same as Python's {@code _enqueue_export}. */
    public static final long EXPORT_JOB_TTL_SECONDS = 3600;

    private final ExportJobRepository exportJobRepository;

    public ExportJobWorker(ExportJobRepository exportJobRepository) {
        this.exportJobRepository = exportJobRepository;
    }

    @Async
    public void buildAndStore(String jobId, String title, List<String> headers, List<List<Object>> rows, String exportType) {
        try {
            byte[] data = "xlsx".equals(exportType)
                    ? XlsxExportBuilder.build(title, headers, rows)
                    : PdfExportBuilder.build(title, headers, rows);

            exportJobRepository.findById(jobId).ifPresent(job -> {
                job.setStatus("completed");
                job.setContent(data);
                job.setFilename("export_" + jobId + "." + exportType);
                job.setPath(null);
                job.setExpiresAt(nowEpochSeconds() + EXPORT_JOB_TTL_SECONDS);
                exportJobRepository.save(job);
            });
        } catch (RuntimeException e) {
            log.warn("export worker failed (job {}): {}", jobId, e.getMessage());
            exportJobRepository.findById(jobId).ifPresent(job -> markFailed(job));
        }
    }

    private void markFailed(ExportJob job) {
        job.setStatus("failed");
        job.setPath(null);
        job.setExpiresAt(nowEpochSeconds() + EXPORT_JOB_TTL_SECONDS);
        exportJobRepository.save(job);
    }

    private static double nowEpochSeconds() {
        return System.currentTimeMillis() / 1000.0;
    }
}
