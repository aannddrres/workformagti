package ge.magti.portal.audit;

import ge.magti.portal.web.AuditChainHealthResponse;
import ge.magti.portal.web.AuditVerifyResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mirrors routers/audit_logs.py's verify_audit_log (:324-376) and
 * audit_chain_health (:379-455). Both queries call the SAME
 * audit_logs_canonical_string PL/SQL function that V28's trigger uses to
 * write row_hash in the first place, so a recompute can never silently
 * drift from what was actually hashed at insert time.
 *
 * <p>Unlike the Python original, there is no SQLite/dialect fallback here:
 * this Java port only ever targets Oracle, so the chain always exists --
 * the {@code status: "unavailable"} case from schemas.py's
 * AuditChainHealthResponse doesn't apply.
 */
@Service
public class AuditChainService {

    private final JdbcTemplate jdbcTemplate;

    public AuditChainService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static String canonicalCall(String alias) {
        return "audit_logs_canonical_string(" + alias + ".id, " + alias + ".prev_hash, "
                + alias + ".admin_id, " + alias + ".action, " + alias + ".item_type, " + alias + ".item_id, "
                + alias + ".timestamp, " + alias + ".category, " + alias + ".details, "
                + alias + ".admin_name_snapshot, " + alias + ".admin_email_snapshot, "
                + alias + ".item_name_snapshot, " + alias + ".ip_address, " + alias + ".user_agent)";
    }

    /**
     * Rows that name no predecessor. Written as the genesis guard's own
     * expression (V28's ux_audit_logs_chain_genesis) so Oracle can answer it
     * from that index.
     */
    private static final String GENESIS_ROWS = "(SELECT COUNT(*) FROM audit_logs "
            + "WHERE CASE WHEN prev_hash IS NULL AND row_hash IS NOT NULL THEN 1 END = 1)";

    /**
     * Whether a row's link to the chain holds: the row its prev_hash names is
     * there exactly once, and this row is that row's only successor. The one
     * row that names nothing is the genesis, and there may be only one.
     *
     * <p>This is the chain as V28's trigger writes it, and it is <b>not</b>
     * id order. The trigger links a row to the tip it finds once it holds the
     * tip's lock; the row's id was taken earlier, when its insert started. So
     * when audited writes overlap, a row can follow a higher id, and a check
     * that took the previous id as the predecessor called an intact chain
     * tampered -- 61 times in 442 rows under a 50-request burst
     * (ConcurrentAuditChainIntegrationTest).
     *
     * <p>What tampering does to these counts: a deleted row leaves its
     * successor naming nothing; a row re-pointed at another predecessor gives
     * that predecessor two successors; a forged second genesis makes two rows
     * that name nothing. An edited row fails its hash instead. The successor
     * count is what catches a branch grafted onto the chain and made the tip:
     * the walk from that tip passes only through rows that verify, and the
     * genuine rows after the graft point are simply no longer on it.
     */
    static boolean linked(String prevHash, long predecessors, long successors, long genesisRows) {
        if (prevHash == null) {
            return genesisRows == 1;
        }
        return predecessors == 1 && successors == 1;
    }

    /**
     * Re-derives a row's hash and its link to its predecessor and compares
     * against what's stored, to prove (or disprove) the row hasn't been
     * altered since V28's trigger wrote it. Empty means no such row.
     */
    public Optional<AuditVerifyResponse> verify(Long id) {
        String sql = "SELECT al.row_hash, al.prev_hash, "
                + "LOWER(RAWTOHEX(STANDARD_HASH(" + canonicalCall("al") + ", 'SHA256'))) AS recomputed_hash, "
                + "(SELECT COUNT(*) FROM audit_logs prev WHERE prev.row_hash = al.prev_hash) AS predecessor_count, "
                + "(SELECT COUNT(*) FROM audit_logs s WHERE s.prev_hash = al.prev_hash) AS successor_count, "
                + GENESIS_ROWS + " AS genesis_rows "
                + "FROM audit_logs al "
                + "WHERE al.id = ?";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        String rowHash = (String) row.get("row_hash");
        if (rowHash == null) {
            return Optional.of(AuditVerifyResponse.unchained());
        }
        String recomputedHash = (String) row.get("recomputed_hash");
        String prevHash = (String) row.get("prev_hash");

        boolean hashMatch = rowHash.equals(recomputedHash);
        // Identity values are allocated before the trigger obtains the tip
        // lock. Concurrent commits can therefore form a valid non-ID order,
        // so the link is looked up by hash (see linked()).
        boolean chainMatch = linked(prevHash, count(row, "predecessor_count"),
                count(row, "successor_count"), count(row, "genesis_rows"));
        return Optional.of(AuditVerifyResponse.of(hashMatch, chainMatch, rowHash, recomputedHash));
    }

    /**
     * Batch-validates the last N chained rows in one pass, so a dashboard
     * can show chain health on mount instead of forcing per-row verify
     * clicks. n is clamped to [1, 500].
     */
    public AuditChainHealthResponse chainHealth(int requestedWindow) {
        int n = Math.max(1, Math.min(requestedWindow, 500));

        // One statement means one Oracle read-consistent snapshot for the
        // state, links, boundary and legacy count, even during inserts.
        // Follow hashes from the committed tip, never identity order. V49's
        // unique row_hash index makes each predecessor unambiguous and lets
        // Oracle seek by hash instead of grouping the whole history.
        String windowSql = """
                WITH state AS (
                    SELECT COUNT(*) state_count, MAX(tip_hash) tip_hash
                    FROM audit_chain_state WHERE id = 1
                ), recent AS (
                    SELECT id, LEVEL depth, CONNECT_BY_ISCYCLE is_cycle
                    FROM audit_logs
                    START WITH row_hash = (SELECT tip_hash FROM state)
                    CONNECT BY NOCYCLE PRIOR prev_hash = row_hash AND LEVEL <= ?
                ), summary AS (
                    SELECT s.state_count, s.tip_hash,
                        (SELECT COUNT(*) FROM audit_logs
                            WHERE CASE WHEN row_hash IS NULL THEN id END IS NOT NULL) unchained_total,
                        (SELECT CASE WHEN EXISTS (
                            SELECT 1 FROM audit_logs WHERE row_hash IS NOT NULL
                        ) THEN 1 ELSE 0 END FROM dual) chained_exists,
                        (SELECT COUNT(*) FROM audit_logs WHERE row_hash = s.tip_hash) tip_count,
                        (SELECT COUNT(*) FROM audit_logs WHERE prev_hash = s.tip_hash
                            AND row_hash IS NOT NULL) tip_children,
                        %s genesis_rows
                    FROM state s
                )
                SELECT s.*, a.id, a.prev_hash, a.row_hash, r.is_cycle,
                    (SELECT COUNT(*) FROM audit_logs p WHERE p.row_hash = a.prev_hash)
                        predecessor_count,
                    (SELECT COUNT(*) FROM audit_logs f WHERE f.prev_hash = a.prev_hash)
                        successor_count,
                """.formatted(GENESIS_ROWS)
                + "CASE WHEN a.id IS NOT NULL THEN LOWER(RAWTOHEX(STANDARD_HASH("
                + canonicalCall("a") + ", 'SHA256'))) END AS recomputed "
                + "FROM summary s LEFT JOIN recent r ON 1 = 1 "
                + "LEFT JOIN audit_logs a ON a.id = r.id "
                + "ORDER BY r.depth";

        List<Map<String, Object>> windowRows = jdbcTemplate.queryForList(windowSql, n);
        Map<String, Object> summary = windowRows.get(0); // aggregate exists even for an empty chain
        long unchainedTotal = ((Number) summary.get("unchained_total")).longValue();
        long genesisRows = count(summary, "genesis_rows");
        boolean validTip = ((Number) summary.get("state_count")).intValue() == 1
                && (summary.get("tip_hash") == null
                    ? ((Number) summary.get("chained_exists")).intValue() == 0
                    : ((Number) summary.get("tip_count")).intValue() == 1
                        && ((Number) summary.get("tip_children")).intValue() == 0);

        int hashMismatches = 0;
        int linkBreaks = 0;
        int checked = 0;
        List<Long> badIds = new ArrayList<>();
        for (Map<String, Object> row : windowRows) {
            if (row.get("id") == null) {
                continue; // state-only failure: no deleted ID can be reported
            }
            checked++;
            long id = ((Number) row.get("id")).longValue();
            String rowHash = (String) row.get("row_hash");
            String recomputed = (String) row.get("recomputed");
            String prevHash = (String) row.get("prev_hash");
            boolean bad = false;
            if (!rowHash.equals(recomputed)) {
                hashMismatches++;
                bad = true;
            }
            if (((Number) row.get("is_cycle")).intValue() != 0 || !linked(prevHash,
                    count(row, "predecessor_count"), count(row, "successor_count"), genesisRows)) {
                linkBreaks++;
                bad = true;
            }
            if (bad && badIds.size() < 10) {
                badIds.add(id);
            }
        }

        String status = (validTip && hashMismatches == 0 && linkBreaks == 0) ? "ok" : "tampered";
        return new AuditChainHealthResponse(
                status, checked, n, hashMismatches, linkBreaks, badIds, unchainedTotal, !validTip);
    }

    private static long count(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }
}
