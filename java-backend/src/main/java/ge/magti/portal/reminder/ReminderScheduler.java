package ge.magti.portal.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the portal-only due-soon/overdue delivery sweep every 15 minutes. */
@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);
    private static final long INTERVAL_MS = 15 * 60 * 1000L;
    private final ReminderSweepService sweepService;

    public ReminderScheduler(ReminderSweepService sweepService) {
        this.sweepService = sweepService;
    }

    @Scheduled(initialDelay = 60_000L, fixedDelay = INTERVAL_MS)
    public void sweep() {
        try {
            int delivered = sweepService.runOnce();
            if (delivered > 0) log.info("Delivered {} scheduled portal reminders", delivered);
        } catch (RuntimeException e) {
            log.error("Scheduled reminder sweep failed", e);
        }
    }
}
