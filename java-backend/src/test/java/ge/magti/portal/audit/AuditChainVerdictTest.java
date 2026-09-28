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
 * altered and unlinked to see it counted twice but listed once, a window
 * that starts mid-chain to see the boundary predecessor used, two rows
 * claiming one predecessor, a chain whose order is not id order. Those are
 * arithmetic on values the database hands over, so they are checked here on
 * values a stub hands over instead.
 *
 * <p>{@link StubJdbcTemplate} returns rows shaped like the one consistent
 * snapshot query in {@link AuditChainService#chainHealth}. That couples
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
     * Stands in for the single snapshot call {@code chainHealth} makes and
     * the one {@code verify} makes. A hand-written stub rather than a mock: the
     * varargs on {@code queryForList}/{@code query} make matcher-based
     * stubbing read worse than the thing it replaces, and the recorded
     * fields below are what several of the assertions are about.
     */
    private static final class StubJdbcTemplate extends JdbcTemplate {

        /** The rows the walk from the tip reaches, as the query returns them. */
        private List<Map<String, Object>> rows = List.of();
        /** Rows in the table that the walk does not reach: a branch, another genesis. */
        private List<Map<String, Object>> offTheWalk = List.of();
        private long unchainedTotal;
        private String boundaryPredecessor;

        private Object windowArgument;
        private int snapshotQueries;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (!sql.contains("WITH state AS")) {
                return rows; // verify(id) has its own one-row query
            }
            snapshotQueries++;
            windowArgument = args.length > 0 ? args[0] : null;
            List<Map<String, Object>> snapshot = new ArrayList<>();
            List<Map<String, Object>> window = rows.isEmpty() ? List.of(Map.of()) : rows;
            for (Map<String, Object> row : window) {
                Map<String, Object> shaped = new LinkedHashMap<>(row);
                shaped.putIfAbsent("id", null);
                shaped.put("state_count", 1);
                shaped.put("tip_hash", rows.isEmpty() ? null : rows.get(rows.size() - 1).get("row_hash"));
                shaped.put("unchained_total", unchainedTotal);
                shaped.put("chained_exists", rows.isEmpty() ? 0 : 1);
                shaped.put("tip_count", rows.isEmpty() ? 0 : 1);
                shaped.put("tip_children", 0);
                shaped.put("is_cycle", 0);
                shaped.put("genesis_rows", table().filter(candidate -> candidate.get("prev_hash") == null).count());
                Object predecessor = row.get("prev_hash");
                long count = predecessor == null ? 0 : table()
                        .filter(candidate -> predecessor.equals(candidate.get("row_hash"))).count();
                if (count == 0 && predecessor != null && predecessor.equals(boundaryPredecessor)) {
                    count = 1;
                }
                shaped.put("predecessor_count", count);
                shaped.put("successor_count", predecessor == null ? 0 : table()
                        .filter(candidate -> predecessor.equals(candidate.get("prev_hash"))).count());
                snapshot.add(shaped);
            }
            return snapshot;
        }

        private java.util.stream.Stream<Map<String, Object>> table() {
            return java.util.stream.Stream.concat(rows.stream(), offTheWalk.stream());
        }
    }

    /** One row as the window query returns it before snapshot metadata. */
    private static Map<String, Object> row(long id, String prevHash, String rowHash, String recomputed) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("prev_hash", prevHash);
        values.put("row_hash", rowHash);
        values.put("recomputed", recomputed);
        return values;
    }

    /** A row that verifies: what was stored is what recomputes. */
    private static Map<String, Object> intactRow(long id, String prevHash, String rowHash) {
        return row(id, prevHash, rowHash, rowHash);
    }

    private static AuditChainHealthResponse healthOf(StubJdbcTemplate jdbc, int requestedWindow) {
        return new AuditChainService(jdbc).chainHealth(requestedWindow);
    }

    // --- the window argument --------------------------------------------

    /**
     * {@code n} is clamped to [1, 500] before it reaches the query. Zero or
     * a negative would make the hierarchical depth bound invalid rather
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

    /** A removed or reordered predecessor: the row itself is untouched. */
    @Test
    void aRowPointingAtTheWrongPredecessorIsALinkBreak() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.rows = List.of(
                intactRow(1, null, HASH_A),
                intactRow(2, "a-hash-that-is-not-its-predecessors", HASH_B));

        AuditChainHealthResponse health = healthOf(jdbc, 10);

        assertEquals("tampered", health.status());
        assertEquals(0, health.hashMismatches());
        assertEquals(1, health.linkBreaks());
        assertEquals(List.of(2L), health.badIds());
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
                row(2, "wrong-predecessor", HASH_B, "recomputes-to-something-else"));

        AuditChainHealthResponse health = healthOf(jdbc, 10);

        assertEquals(1, health.hashMismatches());
        assertEquals(1, health.linkBreaks());
        assertEquals(List.of(2L), health.badIds());
    }

    /**
     * A row re-pointed at another predecessor, rehashed so its own hash still
     * verifies: that predecessor now has two successors, and a chain has one.
     * Checked one at a time, each of the two is a link break. The walk from
     * the tip passes through only one of them, and that one is a link break
     * too -- which is what catches a branch grafted onto the chain and made the
     * tip, where every row the walk reaches verifies.
     */
    @Test
    void twoRowsClaimingOnePredecessorAreBothLinkBreaks() {
        assertEquals("tampered", verifiedRow(HASH_A, 1, 2, 1).status(), "each of the two, verified alone");

        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.rows = List.of(intactRow(1, null, HASH_A), intactRow(3, HASH_A, HASH_C));
        jdbc.offTheWalk = List.of(intactRow(2, HASH_A, HASH_B));

        AuditChainHealthResponse health = healthOf(jdbc, 10);

        assertEquals("tampered", health.status());
        assertEquals(0, health.hashMismatches());
        assertEquals(1, health.linkBreaks());
        assertEquals(List.of(3L), health.badIds());
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
                intactRow(2, null, HASH_B),
                intactRow(1, HASH_B, HASH_A),
                intactRow(3, HASH_A, HASH_C));

        AuditChainHealthResponse health = healthOf(jdbc, 10);

        assertEquals("ok", health.status());
        assertEquals(0, health.linkBreaks());
        assertEquals("ok", verifiedRow(HASH_B, 1, 1, 1).status(), "row 1 alone, after a higher id");
    }

    /**
     * V28's unique index already refuses a second genesis
     * (AuditChainServiceTest); this is the verdict if that index is gone. A
     * forged genesis made the tip leaves a walk of rows that all verify, so
     * the count of rows naming no predecessor is what gives it away.
     */
    @Test
    void aSecondGenesisIsALinkBreak() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.rows = List.of(intactRow(2, null, HASH_B));
        jdbc.offTheWalk = List.of(intactRow(1, null, HASH_A));

        AuditChainHealthResponse health = healthOf(jdbc, 10);

        assertEquals("tampered", health.status());
        assertEquals(1, health.linkBreaks());
        assertEquals(List.of(2L), health.badIds());
        assertEquals("ok", verifiedRow(null, 0, 0, 1).status(), "the genesis, verified alone");
        assertEquals("tampered", verifiedRow(null, 0, 0, 2).status(), "a second genesis, verified alone");
    }

    /**
     * The oldest row in a window that starts mid-chain still gets a real
     * link check, against the chained row just before the window. Without
     * it, tampering that begins exactly at the window's edge -- including a
     * forged genesis row -- would be the one thing the dashboard cannot see.
     */
    @Test
    void theOldestRowInTheWindowIsCheckedAgainstTheRowBeforeIt() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.boundaryPredecessor = HASH_A;
        jdbc.rows = List.of(intactRow(2, HASH_A, HASH_B), intactRow(3, HASH_B, HASH_C));

        assertEquals("ok", healthOf(jdbc, 2).status());
        assertEquals(1, jdbc.snapshotQueries);

        StubJdbcTemplate forgedGenesis = new StubJdbcTemplate();
        forgedGenesis.boundaryPredecessor = HASH_A;
        // The oldest row points outside the sampled window, but that hash
        // is absent from the table. The boundary is still checked.
        forgedGenesis.rows = List.of(intactRow(2, "missing", HASH_B), intactRow(3, HASH_B, HASH_C));

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
    void anEmptyWindowIsOkAndNeverLooksForAPredecessor() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();

        AuditChainHealthResponse health = healthOf(jdbc, 10);

        assertEquals("ok", health.status());
        assertEquals(0, health.checked());
        assertEquals(1, jdbc.snapshotQueries, "even an empty chain uses one consistent snapshot");
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
    }

    /** One row as verify's query returns it: stored and recomputed hashes agree. */
    private static AuditVerifyResponse verifiedRow(
            String prevHash, long predecessors, long successors, long genesisRows) {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("row_hash", HASH_A);
        stored.put("prev_hash", prevHash);
        stored.put("recomputed_hash", HASH_A);
        stored.put("predecessor_count", predecessors);
        stored.put("successor_count", successors);
        stored.put("genesis_rows", genesisRows);
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
