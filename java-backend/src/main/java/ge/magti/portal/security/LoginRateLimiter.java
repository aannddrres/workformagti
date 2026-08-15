package ge.magti.portal.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Login throttling. Descended from state.py's slowapi
 * {@code Limiter(key_func=get_remote_address)} applied at
 * routers/auth.py:28 ({@code @limiter.limit("10/minute")}), but no longer a
 * literal port of it -- see below.
 *
 * <h2>What was wrong (audit PR-04, SEC-04)</h2>
 *
 * The key was the caller's IP alone, taken from {@code getRemoteAddr()}
 * with no forwarded-header handling. Behind this project's own nginx that
 * resolved to the proxy for everyone, so the effective policy was <b>ten
 * login attempts per minute for the entire company</b>: one person with a
 * stale saved password locked out all ~600 staff, and support would see an
 * outage rather than a rate limit. {@link ClientIpResolver} fixes the
 * address; this class fixes what is counted.
 *
 * <h2>Two counters, deliberately</h2>
 *
 * <ul>
 *   <li><b>Per (account, address)</b>, {@value #MAX_ATTEMPTS_PER_ACCOUNT} a
 *       minute -- the real brute-force guard, and the reason one user's
 *       retries can no longer affect anybody else. Keeping the address in
 *       the key matters: keying on the account alone would let anyone lock
 *       a colleague out of their own account on purpose, turning a
 *       protection into a denial-of-service tool.
 *   <li><b>Per address</b>, {@value #MAX_ATTEMPTS_PER_ADDRESS} a minute --
 *       so a single source cannot walk the staff directory trying ten
 *       passwords against each of 600 accounts while never tripping the
 *       first counter. Set well above the per-account limit so a shared
 *       office NAT does not throttle honest users.
 * </ul>
 *
 * <h2>What is still true at more than one replica</h2>
 *
 * The counters are in-memory per JVM. With <i>n</i> replicas the effective
 * limits are <i>n</i>x these numbers, because a load balancer spreads
 * attempts across pods that cannot see each other's state. This is
 * deliberately not solved with Redis here: the Java backend has no Redis
 * dependency, no deployment manifest exists in this repository to wire one
 * into, and adding infrastructure that cannot be tested from here would be
 * the same mistake rejected for PR-03 -- code that looks fixed while the
 * working half lives somewhere nobody can see. It is recorded as a real
 * limit in docs/QUESTIONS_FOR_IT.md instead.
 *
 * <p>Worth stating plainly, though: <i>n</i>x10 per account is a far smaller
 * problem than the 10-for-everyone it replaces. The multi-replica weakness
 * is bounded and per-account; the old behaviour was a company-wide outage
 * one wrong password away.
 *
 * <p>(The Python original had the same shape of flaw for a different reason
 * -- migration doc §5 bug #15, its count fragmented across gunicorn's 4
 * worker processes. Spring Boot's single JVM removes that specific
 * fragmentation; it does not remove this one.)
 */
@Component
public class LoginRateLimiter {

    static final int MAX_ATTEMPTS_PER_ACCOUNT = 10;
    static final int MAX_ATTEMPTS_PER_ADDRESS = 60;
    private static final Duration WINDOW = Duration.ofMinutes(1);

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

    /**
     * Returns true if this login attempt may proceed, false if the caller
     * should be rejected with 429.
     *
     * @param email   as submitted; may be null. Lower-cased for the key so
     *                {@code A@magti.ge} and {@code a@magti.ge} share a bucket
     *                rather than doubling the allowance.
     * @param address the real client address from {@link ClientIpResolver}
     */
    public boolean tryAcquire(String email, String address) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        String normalizedAddress = address == null ? "unknown" : address;

        // Both must be consumed: checking the account counter first and
        // returning early would let the address counter never advance.
        boolean accountOk = consume("acct|" + normalizedEmail + "|" + normalizedAddress, MAX_ATTEMPTS_PER_ACCOUNT);
        boolean addressOk = consume("addr|" + normalizedAddress, MAX_ATTEMPTS_PER_ADDRESS);
        return accountOk && addressOk;
    }

    private boolean consume(String key, int maxAttempts) {
        if (attemptsByKey.size() > MAX_TRACKED_KEYS) {
            attemptsByKey.clear();
        }
        Deque<Instant> attempts = attemptsByKey.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        Instant windowStart = Instant.now().minus(WINDOW);

        synchronized (attempts) {
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(windowStart)) {
                attempts.pollFirst();
            }
            if (attempts.size() >= maxAttempts) {
                return false;
            }
            attempts.addLast(Instant.now());
            return true;
        }
    }
}
