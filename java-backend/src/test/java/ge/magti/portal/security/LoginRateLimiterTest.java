package ge.magti.portal.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRateLimiterTest {

    /** In-memory store: this suite pins the POLICY, not where the counts live. */
    private final LoginRateLimiter limiter = new LoginRateLimiter(new InMemoryLoginAttemptStore());

    @Test
    void allowsUpToTenAttemptsPerAccountThenRejectsTheEleventh() {
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS_PER_ACCOUNT; i++) {
            assertTrue(limiter.tryAcquire("a@magti.ge", "1.2.3.4"), "attempt " + (i + 1) + " should be allowed");
        }
        assertFalse(limiter.tryAcquire("a@magti.ge", "1.2.3.4"), "11th attempt within the window should be rejected");
    }

    /**
     * PR-04's headline symptom. With the old IP-only key and every user
     * resolving to the proxy address, one person retrying a stale password
     * consumed the whole company's budget -- ten attempts a minute for ~600
     * staff, presenting as an outage rather than a rate limit.
     */
    @Test
    void oneUsersRetriesDoNotLockOutColleaguesOnTheSameAddress() {
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS_PER_ACCOUNT; i++) {
            limiter.tryAcquire("locked-out@magti.ge", "10.0.0.1");
        }
        assertFalse(limiter.tryAcquire("locked-out@magti.ge", "10.0.0.1"));

        assertTrue(limiter.tryAcquire("colleague@magti.ge", "10.0.0.1"),
                "a different account behind the same proxy/NAT must still be able to log in");
    }

    /**
     * The other half of the same key. Keying on the account ALONE would let
     * anyone lock a colleague out on purpose from anywhere -- turning the
     * protection into a denial-of-service tool -- so the address stays in it.
     */
    @Test
    void theSameAccountFromADifferentAddressHasItsOwnBudget() {
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS_PER_ACCOUNT; i++) {
            limiter.tryAcquire("victim@magti.ge", "203.0.113.9");
        }
        assertFalse(limiter.tryAcquire("victim@magti.ge", "203.0.113.9"));

        assertTrue(limiter.tryAcquire("victim@magti.ge", "10.0.0.5"),
                "an attacker exhausting the budget from their own address must not lock the real user out");
    }

    /**
     * Without a per-address counter, the per-account key would be a gap: one
     * source could try ten passwords against each of 600 accounts and never
     * trip anything.
     */
    @Test
    void oneAddressCannotWalkTheDirectoryUnboundedly() {
        int allowed = 0;
        for (int account = 0; account < 200; account++) {
            if (limiter.tryAcquire("user" + account + "@magti.ge", "198.51.100.7")) {
                allowed++;
            }
        }
        assertTrue(allowed <= LoginRateLimiter.MAX_ATTEMPTS_PER_ADDRESS,
                "a single address must be capped across accounts, was " + allowed);
        assertTrue(allowed > LoginRateLimiter.MAX_ATTEMPTS_PER_ACCOUNT,
                "the address cap must be looser than the account cap, or shared office NAT throttles honest users");
    }

    /** Case is not a second budget: A@ and a@ are the same account. */
    @Test
    void emailCasingDoesNotDoubleTheAllowance() {
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS_PER_ACCOUNT; i++) {
            limiter.tryAcquire("Mixed.Case@Magti.ge", "1.1.1.1");
        }
        assertFalse(limiter.tryAcquire("mixed.case@magti.ge", "1.1.1.1"));
    }

    /** A null email (malformed request) must not throw on the pre-auth path. */
    @Test
    void aNullEmailIsCountedRatherThanCrashing() {
        assertTrue(limiter.tryAcquire(null, "1.1.1.2"));
        for (int i = 1; i < LoginRateLimiter.MAX_ATTEMPTS_PER_ACCOUNT; i++) {
            limiter.tryAcquire(null, "1.1.1.2");
        }
        assertFalse(limiter.tryAcquire(null, "1.1.1.2"));
    }

    /**
     * The account counter must not short-circuit the address counter: if a
     * rejected account attempt skipped consuming the address budget, an
     * attacker could hammer one account for free and keep a full directory
     * walk in reserve.
     */
    @Test
    void aRejectedAccountAttemptStillConsumesTheAddressBudget() {
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS_PER_ADDRESS + 5; i++) {
            limiter.tryAcquire("hammered@magti.ge", "192.0.2.50");
        }
        assertFalse(limiter.tryAcquire("someone-else@magti.ge", "192.0.2.50"),
                "the address budget should already be exhausted by the rejected attempts above");
    }
}
