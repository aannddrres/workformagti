package ge.magti.portal.audit;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.util.TbilisiTime;
import ge.magti.portal.web.AuditVerifyResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves V28's real Oracle trigger + AuditChainService's recompute-and-
 * compare logic actually detect tampering -- not just that rows insert
 * without error. Exercises three scenarios: altering a row
 * post-hoc, deleting a predecessor, and forging a second genesis row.
 *
 * <p>{@code @Transactional} rolls every test back afterward, same
 * convention as {@link ge.magti.portal.repository.OracleRoundTripTest}.
 */
@RequiresOracle
@SpringBootTest
@Transactional
class AuditChainServiceTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private AuditChainService auditChainService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSource dataSource;

    private User newTestUser(String emailLocalPart) {
        User user = new User();
        user.setEmail(emailLocalPart + "@magti.ge");
        user.setName("ჯაჭვის ტესტ მომხმარებელი");
        return userRepository.saveAndFlush(user);
    }

    private AuditLog newAuditRow(Long adminId, String action, String itemType) {
        AuditLog log = new AuditLog();
        log.setAdminId(adminId);
        log.setAction(action);
        log.setItemType(itemType);
        log.setItemId(adminId);
        log.setTimestamp(TbilisiTime.now());
        return auditLogRepository.saveAndFlush(log);
    }

    @Test
    void chainLinksAcrossSequentialInsertsThroughTheRealJpaSavePath() {
        User admin = newTestUser("chain.link.test");
        AuditLog row1 = newAuditRow(admin.getId(), "LOGIN", "user");
        AuditLog row2 = newAuditRow(admin.getId(), "UPDATE", "article");
        AuditLog row3 = newAuditRow(admin.getId(), "DELETE", "news");

        AuditVerifyResponse v1 = auditChainService.verify(row1.getId()).orElseThrow();
        AuditVerifyResponse v2 = auditChainService.verify(row2.getId()).orElseThrow();
        AuditVerifyResponse v3 = auditChainService.verify(row3.getId()).orElseThrow();

        assertEquals("ok", v1.status());
        assertEquals("ok", v2.status());
        assertEquals("ok", v3.status());
        assertTrue(v1.hashMatch() && v1.chainMatch());
        assertTrue(v2.hashMatch() && v2.chainMatch());
        assertTrue(v3.hashMatch() && v3.chainMatch());
    }

    @Test
    void verifyReturnsEmptyForAMissingRow() {
        assertTrue(auditChainService.verify(999_999_999L).isEmpty());
    }

    @Test
    void verifyDetectsDirectRowTampering() {
        User admin = newTestUser("tamper.row.test");
        AuditLog row = newAuditRow(admin.getId(), "UPDATE", "article");
        // Post-hoc alteration bypassing the ORM entirely -- the trigger only
        // ever runs on INSERT, so this simulates an attacker (or a bug)
        // editing a stored row directly.
        jdbcTemplate.update("UPDATE audit_logs SET details = ? WHERE id = ?",
                "{\"changed\":{\"title\":{\"old\":\"A\",\"new\":\"HACKED\"}}}", row.getId());

        AuditVerifyResponse verified = auditChainService.verify(row.getId()).orElseThrow();

        assertEquals("tampered", verified.status());
        assertFalse(verified.hashMatch());
    }

    @Test
    void verifyDetectsADeletedPredecessor() {
        User admin = newTestUser("tamper.predecessor.test");
        AuditLog row1 = newAuditRow(admin.getId(), "LOGIN", "user");
        AuditLog row2 = newAuditRow(admin.getId(), "UPDATE", "article");
        AuditLog row3 = newAuditRow(admin.getId(), "DELETE", "news");

        jdbcTemplate.update("DELETE FROM audit_logs WHERE id = ?", row2.getId());

        AuditVerifyResponse verified = auditChainService.verify(row3.getId()).orElseThrow();

        assertEquals("tampered", verified.status());
        assertTrue(verified.hashMatch(), "row3's own content is untouched -- only the link is broken");
        assertFalse(verified.chainMatch());
    }

    @Test
    void genesisGuardRejectsAFakeSecondGenesisRow() {
        User admin = newTestUser("genesis.guard.test");
        AuditLog row1 = newAuditRow(admin.getId(), "LOGIN", "user");
        AuditLog row2 = newAuditRow(admin.getId(), "UPDATE", "article");

        // row1 is the true genesis (prev_hash already NULL). Forging row2
        // into a second genesis must be rejected by the functional unique
        // index, not silently accepted.
        assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("UPDATE audit_logs SET prev_hash = NULL WHERE id = ?", row2.getId()));
    }

    @Test
    void chainHealthReportsOkWithNoTampering() {
        User admin = newTestUser("chain.health.clean.test");
        AuditLog row1 = newAuditRow(admin.getId(), "LOGIN", "user");
        AuditLog row2 = newAuditRow(admin.getId(), "UPDATE", "article");
        AuditLog row3 = newAuditRow(admin.getId(), "DELETE", "news");

        // chainHealth(N) checks the last N rows of the WHOLE audit_logs
        // table (a real, ever-growing chain -- see AuditChainService's
        // windowSql), not just rows this test created. This dev Oracle
        // schema accumulates real audit rows from manual verification
        // across the whole migration, so asserting an exact global
        // checked() count is environment-dependent -- that was this
        // test's actual bug (confirmed: fails "expected 3 but was 10"
        // once the table has 10+ real rows, passes on a freshly migrated
        // empty schema). Assert instead on what this test can actually
        // control: nothing in the window is flagged bad, including its
        // own 3 fresh rows specifically.
        var health = auditChainService.chainHealth(10);

        assertEquals("ok", health.status());
        assertEquals(0, health.hashMismatches());
        assertEquals(0, health.linkBreaks());
        assertTrue(health.badIds().isEmpty());
        assertFalse(health.badIds().contains(row1.getId()));
        assertFalse(health.badIds().contains(row2.getId()));
        assertFalse(health.badIds().contains(row3.getId()));
    }

    @Test
    void chainHealthDetectsTamperingWithinTheWindow() {
        User admin = newTestUser("chain.health.tampered.test");
        AuditLog row1 = newAuditRow(admin.getId(), "LOGIN", "user");
        AuditLog row2 = newAuditRow(admin.getId(), "UPDATE", "article");
        newAuditRow(admin.getId(), "DELETE", "news");

        jdbcTemplate.update("UPDATE audit_logs SET admin_name_snapshot = ? WHERE id = ?",
                "Someone Else", row2.getId());

        var health = auditChainService.chainHealth(10);

        assertEquals("tampered", health.status());
        assertEquals(1, health.hashMismatches());
        assertTrue(health.badIds().contains(row2.getId()));
    }

    @Test
    void deletingTheTailIsDetectedWithoutAnySubsequentInsert() {
        User admin = newTestUser("tail.deleted.test");
        newAuditRow(admin.getId(), "LOGIN", "user");
        AuditLog tail = newAuditRow(admin.getId(), "UPDATE", "article");
        jdbcTemplate.update("DELETE FROM audit_logs WHERE id = ?", tail.getId());
        var health = auditChainService.chainHealth(10);
        assertEquals("tampered", health.status());
        assertTrue(health.tailStateMismatch());
    }

    @Test
    void deletingAllChainedRowsDoesNotMasqueradeAsAnEmptyNewChain() {
        User admin = newTestUser("tail.all.deleted.test");
        newAuditRow(admin.getId(), "LOGIN", "user");
        jdbcTemplate.update("DELETE FROM audit_logs WHERE row_hash IS NOT NULL");
        var health = auditChainService.chainHealth(10);
        assertEquals("tampered", health.status());
        assertTrue(health.tailStateMismatch());
        assertTrue(health.badIds().isEmpty(), "do not invent the ids of deleted rows");
    }

    @Test
    void missingStateAndNullStateWithChainedRowsFailClosed() {
        User admin = newTestUser("state.missing.test");
        newAuditRow(admin.getId(), "LOGIN", "user");
        jdbcTemplate.update("UPDATE audit_chain_state SET tip_hash = NULL WHERE id = 1");
        assertEquals("tampered", auditChainService.chainHealth(10).status());
        jdbcTemplate.update("DELETE FROM audit_chain_state WHERE id = 1");
        assertEquals("tampered", auditChainService.chainHealth(10).status());
    }

    @Test
    void genuineEmptyChainAndClampedWindowAreSupported() {
        jdbcTemplate.queryForObject("SELECT id FROM audit_chain_state WHERE id = 1 FOR UPDATE", Long.class);
        jdbcTemplate.update("DELETE FROM audit_logs");
        jdbcTemplate.update("UPDATE audit_chain_state SET tip_hash = NULL WHERE id = 1");
        var health = auditChainService.chainHealth(0);
        assertEquals("ok", health.status());
        assertFalse(health.tailStateMismatch());
        assertEquals(0, health.checked());
        assertEquals(1, health.window());
        assertEquals(500, auditChainService.chainHealth(999).window());
    }

    @Test
    void statePointingAtAnEarlierRowIsNotAValidTail() {
        User admin = newTestUser("state.earlier.test");
        AuditLog first = newAuditRow(admin.getId(), "LOGIN", "user");
        newAuditRow(admin.getId(), "UPDATE", "article");
        jdbcTemplate.update("UPDATE audit_chain_state SET tip_hash = (SELECT row_hash FROM audit_logs WHERE id = ?) WHERE id = 1",
                first.getId());
        assertEquals("tampered", auditChainService.chainHealth(10).status());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentTransactionsMayCommitInTheOppositeOrderFromAllocatedIds() throws Exception {
        User admin = newTestUser("chain.concurrent." + System.nanoTime());
        String originalTip = jdbcTemplate.queryForObject(
                "SELECT tip_hash FROM audit_chain_state WHERE id = 1", String.class);
        var executor = Executors.newSingleThreadExecutor();
        try {
            try (Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {
                first.setAutoCommit(false);
                second.setAutoCommit(false);
                try {
                    // The first transaction owns the V28 tip lock. The other
                    // allocates its identity and waits inside the trigger.
                    try (PreparedStatement lock = first.prepareStatement(
                            "SELECT tip_hash FROM audit_chain_state WHERE id = 1 FOR UPDATE")) {
                        try (ResultSet ignored = lock.executeQuery()) {
                            assertTrue(ignored.next());
                        }
                    }
                    CountDownLatch attempting = new CountDownLatch(1);
                    var delayed = executor.submit(() -> {
                        attempting.countDown();
                        return insertConcurrentAudit(second, admin.getId(), "EARLIER_ID");
                    });
                    assertTrue(attempting.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> delayed.get(1, TimeUnit.SECONDS));

                    long laterId = insertConcurrentAudit(first, admin.getId(), "LATER_ID");
                    first.commit();
                    long earlierId = delayed.get(10, TimeUnit.SECONDS);
                    second.commit();
                    assertTrue(earlierId < laterId, "identity allocation preceded the first commit");
                    assertEquals("ok", auditChainService.verify(earlierId).orElseThrow().status());
                    assertEquals("ok", auditChainService.chainHealth(2).status());
                } finally {
                    first.rollback();
                    second.rollback();
                }
            }
        } finally {
            executor.shutdownNow();
            // Remove only this synthetic fixture and restore the tip. This
            // method has two committed connections, unlike the rollback-only
            // tests above; it runs solely in a fresh disposable test schema.
            jdbcTemplate.update("DELETE FROM audit_logs WHERE admin_id = ? AND item_type = 'chain_fixture'",
                    admin.getId());
            jdbcTemplate.update("UPDATE audit_chain_state SET tip_hash = ? WHERE id = 1", originalTip);
            userRepository.deleteById(admin.getId());
        }
    }

    private static long insertConcurrentAudit(Connection connection, Long adminId, String action)
            throws java.sql.SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO audit_logs (admin_id, action, item_type, item_id, timestamp)
                VALUES (?, ?, 'chain_fixture', ?, CURRENT_TIMESTAMP)
                """)) {
            insert.setLong(1, adminId);
            insert.setString(2, action);
            insert.setLong(3, adminId);
            insert.executeUpdate();
        }
        try (PreparedStatement lookup = connection.prepareStatement(
                "SELECT id FROM audit_logs WHERE admin_id = ? AND action = ? AND item_type = 'chain_fixture'")) {
            lookup.setLong(1, adminId);
            lookup.setString(2, action);
            try (ResultSet result = lookup.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }
}
