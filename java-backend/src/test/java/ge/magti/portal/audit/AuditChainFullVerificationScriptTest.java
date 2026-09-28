package ge.magti.portal.audit;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.docs.RepoRoot;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs scripts/audit/verify_full_chain.sql, the read-only check a DBA runs
 * on a restored backup, against the real schema: the SQL a DBA is handed
 * has to still match V28's columns and function, and has to still tell an
 * intact chain from a damaged one.
 *
 * <p>The script checks the whole table, so the verdict also covers rows
 * other tests committed; an intact chain is what this database should hold.
 * The damage here is rolled back with the test.
 */
@RequiresOracle
@SpringBootTest
@Transactional
class AuditChainFullVerificationScriptTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    private AuditLog first;
    private AuditLog second;
    private AuditLog newest;

    @BeforeEach
    void threeChainedRows() {
        User admin = new User();
        admin.setEmail("chain.full.verify.test@magti.ge");
        admin.setName("ჯაჭვის ტესტ მომხმარებელი");
        Long adminId = userRepository.saveAndFlush(admin).getId();
        first = newAuditRow(adminId, "LOGIN");
        second = newAuditRow(adminId, "UPDATE");
        newest = newAuditRow(adminId, "DELETE");
    }

    @Test
    void anUntouchedChainIsIntact() throws IOException {
        Map<String, Object> result = verdict();

        assertEquals("INTACT", result.get("verdict"), result.toString());
        assertEquals(List.of(), problemRows());
    }

    @Test
    void anEditedRowIsBroken() throws IOException {
        jdbcTemplate.update("UPDATE audit_logs SET admin_name_snapshot = ? WHERE id = ?", "Someone Else", second.getId());

        Map<String, Object> result = verdict();

        assertEquals("BROKEN", result.get("verdict"));
        assertEquals(1, count(result, "hash_mismatches"));
        assertTrue(problemRows().contains(second.getId()), "the edited row must be listed");
    }

    @Test
    void aDeletedRowLeavesItsSuccessorNamingNothing() throws IOException {
        jdbcTemplate.update("DELETE FROM audit_logs WHERE id = ?", second.getId());

        Map<String, Object> result = verdict();

        assertEquals("BROKEN", result.get("verdict"));
        assertEquals(1, count(result, "dangling_links"));
        assertTrue(problemRows().contains(newest.getId()), "the row after the gap must be listed");
    }

    /**
     * The one damage the portal's windowed check cannot see: with the newest
     * row gone, every remaining row still links. The tip recorded in
     * audit_chain_state is what gives it away.
     */
    @Test
    void deletingTheNewestRowIsCaughtByTheRecordedTip() throws IOException {
        jdbcTemplate.update("DELETE FROM audit_logs WHERE id = ?", newest.getId());

        Map<String, Object> result = verdict();

        assertEquals("BROKEN", result.get("verdict"));
        assertEquals(0, count(result, "tip_is_the_end"));
        assertEquals(0, count(result, "dangling_links"), "nothing left in the table names the deleted row");
    }

    private Map<String, Object> verdict() throws IOException {
        return jdbcTemplate.queryForList(statements().get(0)).get(0).entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.getKey().toLowerCase(), Map.Entry::getValue));
    }

    private List<Long> problemRows() throws IOException {
        return jdbcTemplate.queryForList(statements().get(1)).stream()
                .map(row -> ((Number) row.get("ID")).longValue())
                .toList();
    }

    /** The script's statements, as SQL*Plus would run them: split at a line-ending semicolon, comments dropped. */
    private static List<String> statements() throws IOException {
        String script = Files.readString(RepoRoot.path("scripts/audit/verify_full_chain.sql"));
        List<String> statements = Arrays.stream(script.split(";\\s*\\n"))
                .filter(chunk -> chunk.contains("WITH chained"))
                .map(chunk -> chunk.substring(chunk.indexOf("WITH chained")).strip())
                .toList();
        assertEquals(2, statements.size(), "the script's two statements");
        return statements;
    }

    private static long count(Map<String, Object> result, String column) {
        return ((Number) result.get(column)).longValue();
    }

    private AuditLog newAuditRow(Long adminId, String action) {
        AuditLog log = new AuditLog();
        log.setAdminId(adminId);
        log.setAction(action);
        log.setItemType("user");
        log.setItemId(adminId);
        log.setTimestamp(TbilisiTime.now());
        return auditLogRepository.saveAndFlush(log);
    }
}
