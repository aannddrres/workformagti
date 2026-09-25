package ge.magti.portal.audit;

import ge.magti.portal.web.AuditChainHealthResponse;
import ge.magti.portal.web.AuditVerifyResponse;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tamper verdict, without a database.
 *
 * <h2>Why this exists next to {@link AuditChainServiceTest}</h2>
 *
 * That test is the one that matters: it inserts through the real JPA path,
 * lets V28's Oracle trigger do the hashing, then alters a row, deletes a
 * predecessor and forges a second genesis, and proves the service notices.
 * Nothing here replaces it, and nothing here would catch a mistake in the
 * SQL or in {@code audit_logs_canonical_string}.
 *
 * <p>What it cannot do is run without Oracle. Tamper-evidence is the whole
 * point of the audit chain, and today the only thing standing behind it is a
 * suite that the DB-free CI job skips entirely -- so a change to the verdict
 * logic reaches the slow job, or a developer's machine, or neither.
 *
 * <p>The verdict logic is also the part the Oracle test reaches least well,
 * because provoking its edges means arranging a specific chain state: eleven
 * tampered rows to see {@code bad_ids} stop at ten, a row that is both
 * altered and unlinked to see it counted twice but listed once, two rows
 * claiming one predecessor, a chain whose order is not id order. Those are
 * arithmetic on values the database hands over, so they are checked here on
 * values a stub hands over instead.
 *
 * <p>{@link StubJdbcTemplate} returns rows shaped exactly like the queries in
 * {@link AuditChainService#chainHealth} and {@link AuditChainService#verify}
 * produce, link counts included. That couples
 * this test to those queries' column names -- deliberately the smallest
 * coupling available, since the alternative is extracting the loop into a
 * pure function, which means editing tamper-detection code that no test
 * available in this environment could re-verify.
 */
class AuditChainVerdictTest {

    private static final String HASH_A = "aa11";
    private static final String HASH_B = "bb22";
    private static final String HASH_C = "cc33";

    /**
     * Stands in for the three calls {@code chainHealth} makes and the one
     * {@code verify} makes. A hand-written stub rather than a mock: the
     * varargs on {@code queryForList}/{@code query} make matcher-based
     * stubbing read worse than the thing it replaces, and the recorded
     * fields below are what several of the assertions are about.
     */
    private static final class StubJdbcTemplate extends JdbcTemplate {

        private List<Map<String, Object>> rows = List.of();
        private long unchainedTotal;
        private long genesisRows = 1;

        private Object windowArgument;
        private boolean askedForGenesisRows;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            windowArgument = args.length > 0 ? args[0] : null;
            return rows;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            if (sql.contains("prev_hash IS NULL")) {
                askedForGenesisRows = true;
                return (T) Long.valueOf(genesisRows);
            }
            return (T) Long.valueOf(unchainedTotal);
        }
    }

    /**
     * One row as the window query returns it. {@code predecessors} is how
     * many rows carry the hash this row names; {@code successors}, how many
     * rows name that same hash, this one included.
     */
    private static Map<String, Object> row(
            long id, String prevHash, String rowHash, String recomputed, long predecessors, long successors) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("prev_hash", prevHash);
        values.put("row_hash", rowHash);
        values.put("recomputed", recomputed);
        values.put("predecessors", predecessors);
        values.put("successors", successors);
        return values;
    }

    /** Linked as the trigger links: its predecessor is there once, and it is that row's only successor. */
    private static Map<String, Object> row(long id, String prevHash, String rowHash, String recomputed) {
        long linked = prevHash == null ? 0 : 1;
        return row(id, prevHash, rowHash, recomputed, linked, linked);
    }

    /** A row that verifies: what was stored is what recomputes, and its link holds. */
    private static Map<String, Object> intactRow(long id, String prevHash, String rowHash) {
        return row(id, prevHash, rowHash, rowHash);
    }

    private static AuditChainHealthResponse healthOf(StubJdbcTemplate jdbc, int requestedWindow) {
        return new AuditChainService(jdbc).chainHealth(requestedWindow);
    }

    // --- the window argument --------------------------------------------

    /**
     * {@code n} is clamped to [1, 500] before it reaches the query. Zero or
     * a negative would make {@code FETCH FIRST ? ROWS ONLY} an error rather
     * than an empty answer, and an unbounded value turns a dashboard mount
     * into a full-table rehash.
     */
    @Test
    void theWindowIsClampedToAtLeastOne() {
        for (int requested : new int[] {0, -1, -500}) {
            StubJdbcTemplate jdbc = new StubJdbcTemplate();
            AuditChainHealthResponse health = healthOf(jdbc, requested);

            assertEquals(1, health.window(), "requested " + requested);
            assertEquals(1, jdbc.windowArgument, "the clamped value must be what the query receives");
        }
    }

    @Test
    void theWindowIsClampedToAtMostFiveHundred() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        AuditChainHealthResponse health = healthOf(jdbc, 10_000);

        assertEquals(500, health.window());
        assertEquals(500, jdbc.windowArgument);
    }

    // --- the verdict ----------------------------------------------------

    @Test
    void anIntactChainFromGenesisIsOk() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.rows = List.of(
                intactRow(1, null, HASH_A),
                intactRow(2, HASH_A, HASH_B),
                intactRow(3, HASH_B, HASH_C));

        AuditChainHealthResponse health = healthOf(jdbc, 10);

        assertEquals("ok", health.status());
        assertEquals(3, health.checked());
        assertEquals(0, health.hashMismatches());
        assertEquals(0, health.linkBreaks());
        assertEquals(List.of(), health.badIds());
    }

    /** An altered row: what is stored no longer recomputes from its columns. */
    @Test
    void aRowThatNoLongerRecomputesIsAHashMismatch() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.rows = List.of(
                intactRow(1, null, HASH_A),
                row(2, HASH_A, HASH_B, "recomputes-to-something-else"),
                intactRow(3, HASH_B, HASH_C));

        AuditChainHealthResponse health = healthOf(jdbc, 10);

        assertEquals("tampered", health.status());
        assertEquals(1, health.hashMismatches());
        assertEquals(0, health.linkBreaks());
        assertEquals(List.of(2L), health.badIds());
    }

    /** A deleted predecessor: the row itself is untouched, but what it names is gone. */
    @Test
    void aRowWhosePredecessorIsGoneIsALinkBreak() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.rows = List.of(
                intactRow(1, null, HASH_A),
                row(2, "a-hash-no-row-carries", HASH_B, HASH_B, 0, 1));

        AuditChainHealthResponse health = healthOf(jdbc, 10);

        assertEquals("tampered", health.status());
        assertEquals(0, health.hashMismatches());
        assertEquals(1, health.linkBreaks());
        assertEquals(List.of(2L), health.badIds());
    }

    /**
     * A row re-pointed at another predecessor, rehashed so its own hash still
     * verifies: that predecessor now has two successors, and a chain has one.
     */
    @Test
    void twoRowsClaimingOnePredecessorAreBothLinkBreaks() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.rows = List.of(
                intactRow(1, null, HASH_A),
                row(2, HASH_A, HASH_B, HASH_B, 1, 2),
                row(3, HASH_A, HASH_C, HASH_C, 1, 2));

        AuditChainHealthResponse health = healthOf(jdbc, 10);

        assertEquals("tampered", health.status());
        assertEquals(2, health.linkBreaks());
        assertEquals(List.of(2L, 3L), health.badIds());
    }

    /**
     * The chain is the order the trigger linked rows in, which is the order
     * their transactions got the tip's lock -- not the order their ids were
     * taken. Overlapping audited writes produce exactly this, and it is intact.
     */
    @Test
    void aChainWhoseOrderIsNotIdOrderIsOk() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        // Chain: 2 (genesis) -> 1 -> 3.
        jdbc.rows = List.of(
                intactRow(1, HASH_B, HASH_A),
                intactRow(2, null, HASH_B),
                intactRow(3, HASH_A, HASH_C));

        AuditChainHealthResponse health = healthOf(jdbc, 10);

        assertEquals("ok", health.status());
        assertEquals(0, health.linkBreaks());
    }

    /**
     * Both counters are independent, but {@code bad_ids} is a set of rows to
     * go and look at -- a row that fails both ways is still one row. Getting
     * this wrong the other way (listing it twice, or counting it once)
     * misreports how much of the chain is damaged.
     */
    @Test
    void aRowThatFailsBothWaysCountsTwiceButIsListedOnce() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.rows = List.of(
                intactRow(1, null, HASH_A),
                row(2, "wrong-predecessor", HASH_B, "recomputes-to-something-else", 0, 1));

        AuditChainHealthResponse health = healthOf(jdbc, 10);

        assertEquals(1, health.hashMismatches());
        assertEquals(1, health.linkBreaks());
        assertEquals(List.of(2L), health.badIds());
    }

    /**
     * The oldest row in a window that starts mid-chain gets the same link
     * check as every other: its predecessor, outside the window, must be
     * there. Without it, tampering that begins exactly at the window's edge
     * -- including a forged genesis row -- would be the one thing the
     * dashboard cannot see.
     */
    @Test
    void theOldestRowInTheWindowIsCheckedLikeAnyOther() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.rows = List.of(intactRow(2, HASH_A, HASH_B), intactRow(3, HASH_B, HASH_C));

        assertEquals("ok", healthOf(jdbc, 2).status());
        assertFalse(jdbc.askedForGenesisRows, "no row in the window claims to be the genesis");

        StubJdbcTemplate forgedGenesis = new StubJdbcTemplate();
        // prev_hash null claims "nothing came before me", but a genesis already exists.
        forgedGenesis.genesisRows = 2;
        forgedGenesis.rows = List.of(intactRow(2, null, HASH_B), intactRow(3, HASH_B, HASH_C));

        AuditChainHealthResponse health = healthOf(forgedGenesis, 2);
        assertEquals("tampered", health.status());
        assertEquals(1, health.linkBreaks());
        assertEquals(List.of(2L), health.badIds());
    }

    /**
     * {@code bad_ids} is a sample for a dashboard, capped at ten so a
     * wholesale rewrite does not return a five-hundred-element list. The
     * counters are not capped -- they are the answer to "how bad is it", and
     * a cap there would make catastrophic damage look like ten rows.
     */
    @Test
    void badIdsStopAtTenWhileTheCountersDoNot() {
        List<Map<String, Object>> tampered = new ArrayList<>();
        tampered.add(intactRow(1, null, HASH_A));
        for (long id = 2; id <= 13; id++) {
            tampered.add(row(id, HASH_A, HASH_A, "recomputes-to-something-else"));
        }

        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.rows = tampered;

        AuditChainHealthResponse health = healthOf(jdbc, 20);

        assertEquals(12, health.hashMismatches(), "every damaged row must be counted");
        assertEquals(10, health.badIds().size(), "the sample stops at ten");
        assertEquals(List.of(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L), health.badIds());
    }

    @Test
    void anEmptyWindowIsOkAndNeverCountsGenesisRows() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();

        AuditChainHealthResponse health = healthOf(jdbc, 10);

        assertEquals("ok", health.status());
        assertEquals(0, health.checked());
        assertFalse(jdbc.askedForGenesisRows,
                "with no rows nothing claims to be the genesis, and the count would be a needless "
                        + "round trip on every dashboard mount");
    }

    /**
     * Rows written before V28 have no hash at all. They are reported so the
     * number is visible, but they are not damage: calling them tampering
     * would light the dashboard red on every deployment carrying history,
     * and a permanently red indicator is one nobody reads.
     */
    @Test
    void rowsPredatingTheChainAreCountedButDoNotMakeItTampered() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.unchainedTotal = 4_812;
        jdbc.rows = List.of(intactRow(1, null, HASH_A), intactRow(2, HASH_A, HASH_B));

        AuditChainHealthResponse health = healthOf(jdbc, 10);

        assertEquals("ok", health.status());
        assertEquals(4_812, health.unchainedTotal());
    }

    // --- single-row verify ----------------------------------------------

    /**
     * A row from before V28 is neither ok nor tampered: there is nothing to
     * compare against. Answering "tampered" would accuse the deployment of
     * damage it did not do.
     */
    @Test
    void verifyReportsUnchainedForARowWithNoHash() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("row_hash", null);
        legacy.put("prev_hash", null);
        legacy.put("recomputed_hash", HASH_A);
        legacy.put("predecessors", 0L);
        legacy.put("successors", 0L);
        jdbc.rows = List.of(legacy);

        Optional<AuditVerifyResponse> verified = new AuditChainService(jdbc).verify(7L);

        assertTrue(verified.isPresent());
        assertEquals("unchained", verified.get().status());
        assertNull(verified.get().hashMatch());
        assertNull(verified.get().chainMatch());
    }

    /**
     * A single row's link is checked the same way as the window's: by the
     * hash it names, not by the row before it in id order.
     */
    @Test
    void verifyFollowsTheHashTheRowNames() {
        assertEquals("ok", verifiedRow(HASH_B, 1, 1, 1).status(), "linked to a predecessor with a higher id");
        assertEquals("tampered", verifiedRow(HASH_B, 0, 1, 1).status(), "its predecessor is gone");
        assertEquals("tampered", verifiedRow(HASH_B, 1, 2, 1).status(), "its predecessor has another successor");
        assertEquals("ok", verifiedRow(null, 0, 0, 1).status(), "the genesis");
        assertEquals("tampered", verifiedRow(null, 0, 0, 2).status(), "a second genesis");
    }

    private static AuditVerifyResponse verifiedRow(
            String prevHash, long predecessors, long successors, long genesisRows) {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.genesisRows = genesisRows;
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("row_hash", HASH_A);
        stored.put("prev_hash", prevHash);
        stored.put("recomputed_hash", HASH_A);
        stored.put("predecessors", predecessors);
        stored.put("successors", successors);
        jdbc.rows = List.of(stored);
        return new AuditChainService(jdbc).verify(1L).orElseThrow();
    }

    /**
     * Either failure alone is enough to call a row tampered. The two
     * booleans stay separate in the response so the answer says which one it
     * was, but neither is survivable on its own.
     */
    @Test
    void verifyIsTamperedWhenEitherTheHashOrTheLinkFails() {
        assertEquals("ok", AuditVerifyResponse.of(true, true, HASH_A, HASH_A).status());
        assertEquals("tampered", AuditVerifyResponse.of(false, true, HASH_A, HASH_B).status());
        assertEquals("tampered", AuditVerifyResponse.of(true, false, HASH_A, HASH_A).status());
        assertEquals("tampered", AuditVerifyResponse.of(false, false, HASH_A, HASH_B).status());
    }
}
