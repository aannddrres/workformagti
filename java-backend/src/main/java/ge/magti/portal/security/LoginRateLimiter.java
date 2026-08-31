package ge.magti.portal.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

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
 * <h2>The counts are shared across replicas (2026-08-31)</h2>
 *
 * They were an in-memory map per JVM, which multiplied every limit above by
 * the replica count: with the two replicas the deployment declares, "ten a
 * minute" was twenty. The number was small, but a control that does not do
 * what its own documentation says is the part worth fixing -- the same
 * defect class as a rollback switch nothing reads.
 *
 * <p>They now live in {@code login_attempts} (V48) behind
 * {@link LoginAttemptStore}, so every pod counts into the same place. No new
 * infrastructure: the login this guards already reads the user from that
 * database on the same request. Redis was the obvious answer and was not
 * taken, because it would mean asking IT to run a service for a table of
 * throwaway counters.
 *
 * <p>(The Python original had the same shape of flaw for a different reason
 * -- migration doc §5 bug #15, its count fragmented across gunicorn's 4
 * worker processes. This fixes both fragmentations, per-process and
 * per-pod.)
 */
@Component
public class LoginRateLimiter {

    static final int MAX_ATTEMPTS_PER_ACCOUNT = 10;
    static final int MAX_ATTEMPTS_PER_ADDRESS = 60;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final LoginAttemptStore store;

    public LoginRateLimiter(LoginAttemptStore store) {
        this.store = store;
    }

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
        boolean accountOk = store.tryConsume(
                "acct|" + normalizedEmail + "|" + normalizedAddress, MAX_ATTEMPTS_PER_ACCOUNT, WINDOW);
        boolean addressOk = store.tryConsume(
                "addr|" + normalizedAddress, MAX_ATTEMPTS_PER_ADDRESS, WINDOW);
        return accountOk && addressOk;
    }
}
