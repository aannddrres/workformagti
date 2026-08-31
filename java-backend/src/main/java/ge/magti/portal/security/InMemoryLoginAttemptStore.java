package ge.magti.portal.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Counts inside this JVM. Correct for exactly one instance.
 *
 * <p><b>Not a bean.</b> It is what {@link LoginRateLimiterTest} uses to
 * exercise the policy without a database, and it is here rather than in the
 * test sources so that the behaviour the unit tests pin is the same code
 * shape the JDBC store implements -- a test double written in the test
 * folder tends to drift into whatever makes the tests pass.
 *
 * <p>If it is ever wired into the application again, the limits become
 * n x the configured numbers at n replicas, which is the defect V48 exists
 * to remove.
 */
public class InMemoryLoginAttemptStore implements LoginAttemptStore {

    /**
     * Guards against unbounded growth: every distinct key allocates a deque,
     * and the key includes attacker-controlled input (the email). Without a
     * ceiling, a script posting random addresses is a slow memory leak. When
     * the cap is hit the map is cleared wholesale rather than evicted
     * cleverly -- it costs at most one extra window of attempts, and a
     * simple bound that is obviously correct beats an LRU that is only
     * probably correct on this path.
     */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<String, Deque<Instant>> attemptsByKey = new ConcurrentHashMap<>();

    @Override
    public boolean tryConsume(String key, int maxAttempts, Duration window) {
        if (attemptsByKey.size() > MAX_TRACKED_KEYS) {
            attemptsByKey.clear();
        }
        Deque<Instant> attempts = attemptsByKey.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        Instant windowStart = Instant.now().minus(window);

        synchronized (attempts) {
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(windowStart)) {
                attempts.pollFirst();
            }
            attempts.addLast(Instant.now());
            return attempts.size() <= maxAttempts;
        }
    }
}
