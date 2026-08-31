package ge.magti.portal.security;

import java.time.Duration;

/**
 * Where {@link LoginRateLimiter} keeps its counts.
 *
 * <p>The seam exists because the two halves fail differently. The policy --
 * which counters, keyed by what, at which limits -- is reasoning that
 * belongs under fast tests with no database. Where the counts live is a
 * deployment question, and it is the half that was wrong: an in-JVM map
 * silently multiplied every limit by the replica count.
 */
public interface LoginAttemptStore {

    /**
     * Records one attempt against {@code key} and says whether it may
     * proceed.
     *
     * <p>The attempt is counted <b>before</b> the decision, deliberately.
     * Counting after would let two callers at the boundary each read
     * "nine so far" and both proceed; counting first makes concurrent
     * attempts see each other, so the error is toward refusing rather than
     * admitting. It also means a caller who is already being refused keeps
     * their window full, which is the right behaviour for a brute-force
     * guard.
     *
     * @return true if this attempt is within {@code maxAttempts} for the
     *         window, false if the caller should be refused
     */
    boolean tryConsume(String key, int maxAttempts, Duration window);
}
