package ge.magti.portal.audit;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.util.TbilisiTime;
import ge.magti.portal.web.AuditChainHealthResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two audited writes whose transactions overlap, and what the chain check
 * makes of the result.
 *
 * <p>V28's trigger links a new row to whatever chain tip it finds once it
 * holds the tip's row lock, so rows join the chain in the order their
 * transactions get that lock. Their ids are taken earlier, when the insert
 * starts. Whenever audited writes overlap -- a shift's worth of sign-ins
 * arriving together -- the two orders part: a row can follow, in the chain,
 * a row with a higher id. Nothing is wrong with such a chain, and a check
 * that reads id order as chain order calls it tampered. On the database the
 * load test wrote, 50 simultaneous quiz submissions left 61 such links in
 * 442 rows, every one of which walks cleanly back to genesis by hash.
 *
 * <p>Deterministic rather than a race: the test takes the tip lock itself,
 * starts an insert that takes its id and then waits on that lock, and
 * inserts a second row from the lock holder. The waiting row has the lower id
 * and joins the chain second. Both rows are committed, because the check
 * reads what other transactions see, and they stay: audit rows are never
 * deleted, and deleting one would break the chain for every test after it.
 */
@RequiresOracle
@SpringBootTest
class ConcurrentAuditChainIntegrationTest {

    /** Long enough for the waiting insert to have taken its id and blocked. */
    private static final long HOLD_MILLIS = 1_500;

    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private AuditChainService auditChainService;

    @Test
    void rowsThatJoinedTheChainOutOfIdOrderAreNotReportedAsTampered() throws Exception {
        Long adminId = fixtureUser().getId();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Future<Long>> waiting = new AtomicReference<>();
            Long heldRow = transactionTemplate.execute(status -> {
                jdbcTemplate.queryForObject(
                        "SELECT tip_hash FROM audit_chain_state WHERE id = 1 FOR UPDATE", String.class);
                waiting.set(pool.submit(() -> transactionTemplate.execute(inner -> newAuditRow(adminId, "WAITED"))));
                pause();
                return newAuditRow(adminId, "HELD");
            });
            Long waitedRow = waiting.get().get(30, TimeUnit.SECONDS);

            assertTrue(waitedRow < heldRow, "precondition: the waiting insert took its id first");
            assertEquals(rowHash(heldRow), prevHash(waitedRow),
                    "precondition: the lower id joined the chain after the higher one");

            AuditChainHealthResponse health = auditChainService.chainHealth(10);
            assertEquals("ok", health.status(), "an intact chain was reported as " + health);
            assertEquals("ok", auditChainService.verify(waitedRow).orElseThrow().status());
            assertEquals("ok", auditChainService.verify(heldRow).orElseThrow().status());
        } finally {
            pool.shutdownNow();
        }
    }

    private Long newAuditRow(Long adminId, String action) {
        AuditLog log = new AuditLog();
        log.setAdminId(adminId);
        log.setAction(action);
        log.setItemType("user");
        log.setItemId(adminId);
        log.setTimestamp(TbilisiTime.now());
        return auditLogRepository.saveAndFlush(log).getId();
    }

    private User fixtureUser() {
        User user = new User();
        user.setEmail("chain.overlap." + UUID.randomUUID().toString().substring(0, 8) + "@magti.ge");
        user.setName("ჯაჭვის ტესტ მომხმარებელი");
        return userRepository.saveAndFlush(user);
    }

    private String rowHash(Long id) {
        return jdbcTemplate.queryForObject("SELECT row_hash FROM audit_logs WHERE id = ?", String.class, id);
    }

    private String prevHash(Long id) {
        return jdbcTemplate.queryForObject("SELECT prev_hash FROM audit_logs WHERE id = ?", String.class, id);
    }

    private static void pause() {
        try {
            Thread.sleep(HOLD_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
