package ge.magti.portal.export;

import ge.magti.portal.domain.ExportJob;
import ge.magti.portal.repository.ExportJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Fixes known bug #9 (see {@link ExportJob}'s javadoc): Python writes
 * {@code export_jobs.expires_at} on every job but never once reads it back
 * -- a finished export file sits on disk indefinitely unless someone
 * actually downloads it (which triggers {@code _cleanup_export}). This
 * periodic sweep is the missing other half: anything past its TTL --
 * completed, failed, or a job whose worker crashed mid-build -- gets its
 * file deleted and its row dropped. User-approved fix, 2026-08-06 (raised
 * the moment this layer was reached, per this port's own "surface bugs
 * immediately" rule).
 */
@Component
public class ExportJobCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExportJobCleanupScheduler.class);
    private static final long SWEEP_INTERVAL_MS = 600_000; // 10 minutes

    private final ExportJobRepository exportJobRepository;

    public ExportJobCleanupScheduler(ExportJobRepository exportJobRepository) {
        this.exportJobRepository = exportJobRepository;
    }

    @Scheduled(initialDelay = SWEEP_INTERVAL_MS, fixedDelay = SWEEP_INTERVAL_MS)
    public void sweepExpiredJobs() {
        double now = System.currentTimeMillis() / 1000.0;
        List<ExportJob> stale = exportJobRepository.findByExpiresAtLessThan(now);
        if (stale.isEmpty()) {
            return;
        }
        for (ExportJob job : stale) {
            deleteFileIfPresent(job.getPath());
        }
        exportJobRepository.deleteAll(stale);
        log.info("export-job cleanup: removed {} expired job(s)", stale.size());
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
