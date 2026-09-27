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
     * Re-derives a row's hash and its link to its predecessor and compares
     * against what's stored, to prove (or disprove) the row hasn't been
     * altered since V28's trigger wrote it. Empty means no such row.
     */
    public Optional<AuditVerifyResponse> verify(Long id) {
        String sql = "SELECT al.row_hash, al.prev_hash, "
                + "LOWER(RAWTOHEX(STANDARD_HASH(" + canonicalCall("al") + ", 'SHA256'))) AS recomputed_hash, "
                + "(SELECT COUNT(*) FROM audit_logs prev WHERE prev.row_hash = al.prev_hash) AS predecessor_count "
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
        int predecessorCount = ((Number) row.get("predecessor_count")).intValue();

        boolean hashMatch = rowHash.equals(recomputedHash);
        // Identity values are allocated before the trigger obtains the tip
        // lock. Concurrent commits can therefore form a valid non-ID order.
        boolean chainMatch = prevHash == null || predecessorCount == 1;
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
                            AND row_hash IS NOT NULL) tip_children
                    FROM state s
                )
                SELECT s.*, a.id, a.prev_hash, a.row_hash, r.is_cycle,
                    (SELECT COUNT(*) FROM audit_logs p WHERE p.row_hash = a.prev_hash)
                        predecessor_count,
                """
                + "CASE WHEN a.id IS NOT NULL THEN LOWER(RAWTOHEX(STANDARD_HASH("
                + canonicalCall("a") + ", 'SHA256'))) END AS recomputed "
                + "FROM summary s LEFT JOIN recent r ON 1 = 1 "
                + "LEFT JOIN audit_logs a ON a.id = r.id "
                + "ORDER BY r.depth";

        List<Map<String, Object>> windowRows = jdbcTemplate.queryForList(windowSql, n);
        Map<String, Object> summary = windowRows.get(0); // aggregate exists even for an empty chain
        long unchainedTotal = ((Number) summary.get("unchained_total")).longValue();
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
            if (((Number) row.get("is_cycle")).intValue() != 0
                    || (prevHash != null && ((Number) row.get("predecessor_count")).intValue() != 1)) {
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
}
