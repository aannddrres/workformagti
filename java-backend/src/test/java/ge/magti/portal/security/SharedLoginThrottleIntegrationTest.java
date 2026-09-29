package ge.magti.portal.security;

import ge.magti.portal.RequiresOracle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The defect this was written for: the login throttle counted in each JVM's
 * own memory, so the "ten attempts per minute" the documentation promised
 * was ten <i>per pod</i>. At the two replicas
 * {@code k8s/30-backend-deployment.yaml} declares, an attacker got twenty.
 *
 * <p>{@link LoginRateLimiterTest} covers the policy -- which counters, keyed
 * by what -- against an in-memory store, and is fast. This covers the one
 * thing that test cannot see, because it needs two independent limiters and
 * a real database between them: that a second instance counts into the same
 * budget as the first rather than opening a fresh one.
 */
@RequiresOracle
@SpringBootTest
@Transactional
class SharedLoginThrottleIntegrationTest {

    @Autowired private JdbcLoginAttemptStore store;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** A distinct address per test, so runs cannot inherit each other's counts. */
    private String freshAddress() {
        return "203.0.113." + Math.abs(UUID.randomUUID().hashCode() % 250);
    }

    @Test
    void twoInstancesShareOneBudgetInsteadOfGettingOneEach() {
        // Two limiters over ONE store: what two pods behind a load balancer
        // are, as far as this control is concerned.
        LoginRateLimiter podA = new LoginRateLimiter(store);
        LoginRateLimiter podB = new LoginRateLimiter(store);

        String email = "throttle-" + UUID.randomUUID() + "@magti.ge";
        String address = freshAddress();

        int allowed = 0;
        // Alternating, because a load balancer does not send a burst to one
        // pod. Twenty attempts is exactly what the old behaviour let through.
        for (int attempt = 0; attempt < 20; attempt++) {
            LoginRateLimiter pod = attempt % 2 == 0 ? podA : podB;
            if (pod.tryAcquire(email, address)) {
                allowed++;
            }
        }

        assertEquals(LoginRateLimiter.MAX_ATTEMPTS_PER_ACCOUNT, allowed,
                "two instances let " + allowed + " attempts through against a limit of "
                        + LoginRateLimiter.MAX_ATTEMPTS_PER_ACCOUNT
                        + "; the counts are not shared, which is the defect V48 exists to remove");
    }

    @Test
    void aColleagueOnTheSameAddressIsUnaffected() {
        // The reason the account counter keys on (account, address) at all:
        // one person's stale saved password must never lock out the office
        // around them. Worth re-asserting against the shared store, since
        // this is where every pod's attempts now meet.
        LoginRateLimiter limiter = new LoginRateLimiter(store);
        String address = freshAddress();
        String locked = "locked-" + UUID.randomUUID() + "@magti.ge";
        String colleague = "colleague-" + UUID.randomUUID() + "@magti.ge";

        for (int attempt = 0; attempt < LoginRateLimiter.MAX_ATTEMPTS_PER_ACCOUNT + 5; attempt++) {
            limiter.tryAcquire(locked, address);
        }

        assertFalse(limiter.tryAcquire(locked, address), "the exhausted account must stay refused");
        assertTrue(limiter.tryAcquire(colleague, address), "a colleague on the same address must still get in");
    }

    @Test
    void anAttemptOutsideTheWindowNoLongerCounts() {
        String key = "window-" + UUID.randomUUID();

        // An attempt from five minutes ago, written directly so its age is
        // exact. Doing this by making two calls and asking about a
        // sub-millisecond window does not work: Instant.now() on some hosts
        // does not advance between two adjacent calls, so both rows land on
        // the same microsecond and the test measures the clock rather than
        // the query.
        insertAttempt(key, Duration.ofMinutes(5));

        // Limit of one, and one attempt already on record -- but that one is
        // outside the minute being asked about, so this must still be let
        // through. If the count were cumulative rather than time-bounded, an
        // account would be locked out permanently after ten bad passwords
        // once, forever.
        assertTrue(store.tryConsume(key, 1, Duration.ofMinutes(1)),
                "an attempt older than the window must not count against the caller");

        assertFalse(store.tryConsume(key, 1, Duration.ofMinutes(1)),
                "the attempt just recorded IS inside the window and must count");
    }

    @Test
    void theSweepRemovesOnlyRowsNoCounterCanStillRead() {
        String key = "sweep-" + UUID.randomUUID();
        store.tryConsume(key, 10, Duration.ofMinutes(1));

        insertAttempt(key, Duration.ofMinutes(60));
        assertEquals(2, countFor(key));

        store.sweepExpiredAttempts();

        assertEquals(1, countFor(key),
                "the sweep must take the hour-old row and leave the one still inside every window");
    }

    /**
     * An attempt {@code age} ago, on the JVM's clock -- the clock
     * JdbcLoginAttemptStore writes and compares with. SYSTIMESTAMP was used
     * here once, and it is the database server's clock in the server's zone:
     * against a +04:00 Oracle and the UTC JVM surefire runs, both tests failed,
     * the rows landing four hours from where the store looks.
     */
    private void insertAttempt(String key, Duration age) {
        jdbcTemplate.update(
                "INSERT INTO login_attempts (attempt_key, attempted_at) VALUES (?, ?)",
                key, Timestamp.from(Instant.now().minus(age)));
    }

    private int countFor(String key) {
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM login_attempts WHERE attempt_key = ?", Integer.class, key);
        return rows == null ? 0 : rows;
    }
}
