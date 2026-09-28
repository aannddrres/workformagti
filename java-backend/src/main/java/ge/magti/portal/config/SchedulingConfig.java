package ge.magti.portal.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Switches on the five timers: export heartbeat, recovery and cleanup,
 * reminders, and the login-attempt sweep.
 *
 * <p>Behind a property only so the test context can leave them off. Each
 * writes audit rows on its own schedule, committed between whatever a test
 * counts (PortalBackendApplicationTests says which failure that caused).
 * Missing means on, so no deployment loses them by leaving the key out.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "portal.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
