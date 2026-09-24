package ge.magti.portal.export;

import ge.magti.portal.repository.ExportJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reaps only expired leases; a live export on another replica is untouched. */
@Component
public class ExportJobRecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(ExportJobRecoveryScheduler.class);

    private final ExportJobRepository jobs;
    private final ExportJobLifecycle lifecycle;

    public ExportJobRecoveryScheduler(ExportJobRepository jobs, ExportJobLifecycle lifecycle) {
        this.jobs = jobs;
        this.lifecycle = lifecycle;
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void recover() {
        for (String jobId : jobs.findExpiredProcessingIds()) {
            try {
                lifecycle.recoverExpired(jobId);
            } catch (RuntimeException e) {
                log.error("Could not recover expired export job {}", jobId, e);
            }
        }
    }
}
