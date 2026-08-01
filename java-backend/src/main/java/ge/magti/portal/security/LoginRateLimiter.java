package ge.magti.portal.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Mirrors state.py's slowapi {@code Limiter(key_func=get_remote_address)}
 * as applied to login ({@code @limiter.limit("10/minute")},
 * routers/auth.py:28) -- per-IP, in-memory, no Redis backend needed at
 * this scale.
 *
 * <p><b>Deliberately does not reproduce known bug #15</b> (migration doc
 * §5, additionally-discovered #15): the Python limiter's count is
 * fragmented across gunicorn's 4 separate worker processes, so the real
 * limit is nominally 4x weaker than "10/minute" suggests. Spring Boot
 * runs as a single JVM by default (no multi-process worker model), so a
 * single shared in-memory counter here is already correct -- nothing
 * extra needed to fix what was a deployment-model artifact on the Python
 * side, not something to guard against here.
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Map<String, Deque<Instant>> attemptsByKey = new ConcurrentHashMap<>();

    /** Returns true if this request may proceed, false if the caller should be rejected (429). */
    public boolean tryAcquire(String key) {
        Deque<Instant> attempts = attemptsByKey.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        Instant windowStart = Instant.now().minus(WINDOW);

        synchronized (attempts) {
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(windowStart)) {
                attempts.pollFirst();
            }
            if (attempts.size() >= MAX_ATTEMPTS) {
                return false;
            }
            attempts.addLast(Instant.now());
            return true;
        }
    }
}
