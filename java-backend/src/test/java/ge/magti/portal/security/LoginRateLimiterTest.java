package ge.magti.portal.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRateLimiterTest {

    private final LoginRateLimiter limiter = new LoginRateLimiter();

    @Test
    void allowsUpToTenAttemptsThenRejectsTheEleventh() {
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("1.2.3.4"), "attempt " + (i + 1) + " should be allowed");
        }
        assertFalse(limiter.tryAcquire("1.2.3.4"), "11th attempt within the window should be rejected");
    }

    @Test
    void differentKeysHaveIndependentBudgets() {
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("5.5.5.5");
        }
        assertFalse(limiter.tryAcquire("5.5.5.5"));
        assertTrue(limiter.tryAcquire("6.6.6.6"), "a different IP must have its own, unconsumed budget");
    }
}
