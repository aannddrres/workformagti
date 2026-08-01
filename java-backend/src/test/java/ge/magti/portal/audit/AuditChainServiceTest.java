package ge.magti.portal.audit;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves V28's real Oracle trigger + AuditChainService's recompute-and-
 * compare logic actually detect tampering -- not just that rows insert
 * without error. Exercises the same three scenarios
 * tests/test_audit_trail.py:331-421 checks on Postgres: altering a row
 * post-hoc, deleting a predecessor, and forging a second genesis row.
 *
 * <p>{@code @Transactional} rolls every test back afterward, same
 * convention as {@link ge.magti.portal.repository.OracleRoundTripTest}.
 */
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
    void chainHealthReportsOkForACleanWindow() {
        User admin = newTestUser("chain.health.clean.test");
        newAuditRow(admin.getId(), "LOGIN", "user");
        newAuditRow(admin.getId(), "UPDATE", "article");
        newAuditRow(admin.getId(), "DELETE", "news");

        var health = auditChainService.chainHealth(10);

        assertEquals("ok", health.status());
        assertEquals(3, health.checked());
        assertEquals(0, health.hashMismatches());
        assertEquals(0, health.linkBreaks());
        assertTrue(health.badIds().isEmpty());
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
}
