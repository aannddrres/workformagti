package ge.magti.portal.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Counts in {@code login_attempts} (V48), so every replica sees the same
 * number.
 *
 * <p>The database is already on this request's critical path -- the login it
 * guards reads the user from the same connection pool -- so this adds a
 * write and a counted read to a request that was going to talk to Oracle
 * regardless. Login volume is a few per second at the very worst.
 *
 * <p><b>The count joins the caller's transaction, deliberately.</b>
 * {@code AuthController.login} is {@code @Transactional}, so a recorded
 * attempt commits with it -- including when the login fails, because a
 * rejected password is a normal return, not an exception. The one case
 * where an attempt would go uncounted is a login that throws and rolls
 * back, which is already a 500 someone is being paged about. Forcing a
 * separate transaction here would close that gap and cost a second
 * connection per attempt; the gap is not worth the connection.
 */
@Component
public class JdbcLoginAttemptStore implements LoginAttemptStore {

    private static final Logger logger = LoggerFactory.getLogger(JdbcLoginAttemptStore.class);

    /** Matches login_attempts.attempt_key; longer keys are truncated, never rejected. */
    private static final int MAX_KEY_LENGTH = 400;

    /** Literal rather than Duration.toMillis(): @Scheduled needs a constant expression. */
    private static final long SWEEP_INTERVAL_MS = 5L * 60 * 1000;

    /**
     * How far back the sweep keeps rows. Comfortably longer than the one
     * minute any counter reads, so a sweep running concurrently with a burst
     * can never delete a row that is still being counted.
     */
    private static final Duration RETENTION = Duration.ofMinutes(15);

    private final JdbcTemplate jdbcTemplate;

    public JdbcLoginAttemptStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean tryConsume(String key, int maxAttempts, Duration window) {
        String storedKey = key.length() > MAX_KEY_LENGTH ? key.substring(0, MAX_KEY_LENGTH) : key;
        Instant now = Instant.now();

        try {
            jdbcTemplate.update(
                    "INSERT INTO login_attempts (attempt_key, attempted_at) VALUES (?, ?)",
                    storedKey, Timestamp.from(now));

            Integer attempts = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM login_attempts WHERE attempt_key = ? AND attempted_at > ?",
                    Integer.class, storedKey, Timestamp.from(now.minus(window)));

            return attempts != null && attempts <= maxAttempts;
        } catch (RuntimeException e) {
            // Fails OPEN, and that is not the hole it looks like: the login
            // this guards reads the user from the same database, so a caller
            // who gets past here still meets the same failure one step
            // later. Failing closed would turn a table-level problem into
            // "nobody in the company can sign in", which is a worse outcome
            // than a throttle being briefly unenforced.
            //
            // Logged at WARN with the key omitted -- it contains the
            // attempted email.
            logger.warn("Login throttle unavailable, allowing the attempt: {}", e.toString());
            return true;
        }
    }

    /**
     * Removes rows no counter can still read.
     *
     * <p>Every replica runs this and that is fine: the delete is idempotent
     * and bounded by an index on {@code attempted_at}. Without it the table
     * would grow by one row per login attempt forever, since the counting
     * query filters by time rather than deleting what it passes over.
     */
    @Scheduled(initialDelay = SWEEP_INTERVAL_MS, fixedDelay = SWEEP_INTERVAL_MS)
    public void sweepExpiredAttempts() {
        try {
            int removed = jdbcTemplate.update(
                    "DELETE FROM login_attempts WHERE attempted_at < ?",
                    Timestamp.from(Instant.now().minus(RETENTION)));
            if (removed > 0) {
                logger.debug("Swept {} expired login attempt(s)", removed);
            }
        } catch (RuntimeException e) {
            logger.warn("Could not sweep login_attempts: {}", e.toString());
        }
    }
}
