package ge.magti.portal.export;

import ge.magti.portal.repository.ExportJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Renews queued and running jobs on the scheduler, away from the export pool. */
@Component
public class ExportJobHeartbeat {
    private static final Logger log = LoggerFactory.getLogger(ExportJobHeartbeat.class);

    private final ExportJobRepository jobs;
    private final ExportJobLeaseOwner owner;
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public ExportJobHeartbeat(ExportJobRepository jobs, ExportJobLeaseOwner owner) {
        this.jobs = jobs;
        this.owner = owner;
    }

    public void track(String jobId) {
        active.add(jobId);
    }

    public void finished(String jobId) {
        active.remove(jobId);
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 15_000)
    public void renew() {
        for (String jobId : active) {
            try {
                if (jobs.renewLease(jobId, owner.id()) != 1) {
                    active.remove(jobId);
                }
            } catch (RuntimeException e) {
                // A transient DB outage must not silently drop the local job.
                // If its lease expires meanwhile, completion refuses to publish.
                log.warn("Could not renew export lease for {}", jobId, e);
            }
        }
    }
}
