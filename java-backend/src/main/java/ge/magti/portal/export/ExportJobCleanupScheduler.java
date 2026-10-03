package ge.magti.portal.export;

import ge.magti.portal.repository.ExportJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Fixes known bug #9 (see {@link ExportJob}'s javadoc): the original app
 * wrote {@code export_jobs.expires_at} on every job but never once read it
 * back -- a finished export file sat on disk indefinitely unless someone
 * actually downloaded it. This
 * periodic sweep is the missing other half: anything past its TTL --
 * completed or failed -- gets its file deleted and its row dropped. A job
 * whose worker crashed mid-build is first marked failed by
 * {@link ExportJobRecoveryScheduler} and remains visible for another hour.
 * User-approved fix, 2026-08-06 (raised
 * the moment this layer was reached, per this port's own "surface bugs
 * immediately" rule).
 *
 * <p><b>Now the only thing that deletes an export (audit BL-09).</b>
 * {@code ExportController.downloadExport} used to delete the file and the row
 * on the first successful read, which made every export single-use: a refresh
 * or a retried download got "not ready yet" for something that no longer
 * existed. Removing that leaves this sweep as the sole owner of expiry, which
 * is what it was written for.
 *
 * <p>The file deletion below is for pre-V31 rows only -- exports built after
 * that migration live in the {@code export_jobs} row itself and are removed
 * with it (audit PR-03).
 */
@Component
public class ExportJobCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExportJobCleanupScheduler.class);
    private static final long SWEEP_INTERVAL_MS = 600_000; // 10 minutes
    private static final int SWEEP_BATCH_SIZE = 500;

    private final ExportJobRepository exportJobRepository;

    public ExportJobCleanupScheduler(ExportJobRepository exportJobRepository) {
        this.exportJobRepository = exportJobRepository;
    }

    @Scheduled(initialDelay = SWEEP_INTERVAL_MS, fixedDelay = SWEEP_INTERVAL_MS)
    public void sweepExpiredJobs() {
        double now = System.currentTimeMillis() / 1000.0;
        int removed = 0;
        while (true) {
            List<ExpiredExportJobReference> stale = exportJobRepository.findExpiredReferences(
                    now, PageRequest.of(0, SWEEP_BATCH_SIZE));
            if (stale.isEmpty()) {
                break;
            }
            for (ExpiredExportJobReference job : stale) {
                deleteFileIfPresent(job.path());
            }
            exportJobRepository.deleteExpiredByIds(
                    stale.stream().map(ExpiredExportJobReference::id).toList(), now);
            removed += stale.size();
        }
        if (removed > 0) {
            log.info("export-job cleanup: removed {} expired job(s)", removed);
        }
    }

    private void deleteFileIfPresent(String path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (IOException e) {
            log.warn("failed to delete stale export file {}: {}", path, e.getMessage());
        }
    }
}
