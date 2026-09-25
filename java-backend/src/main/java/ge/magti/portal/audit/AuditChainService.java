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
    private static final String GENESIS_ROWS_SQL = "SELECT COUNT(*) FROM audit_logs "
            + "WHERE CASE WHEN prev_hash IS NULL AND row_hash IS NOT NULL THEN 1 END = 1";

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
     * that name nothing. An edited row fails its hash instead.
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
                + "(SELECT COUNT(*) FROM audit_logs p WHERE p.row_hash = al.prev_hash) AS predecessors, "
                + "(SELECT COUNT(*) FROM audit_logs s WHERE s.prev_hash = al.prev_hash) AS successors "
                + "FROM audit_logs al WHERE al.id = ?";

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
        // Looks the predecessor up live rather than trusting the stored
        // prev_hash blindly, so an altered or deleted predecessor is caught
        // too, not just a directly altered row.
        long genesisRows = prevHash == null ? genesisRows() : 0;
        boolean chainMatch = linked(prevHash, count(row, "predecessors"), count(row, "successors"), genesisRows);
        return Optional.of(AuditVerifyResponse.of(hashMatch, chainMatch, rowHash, recomputedHash));
    }

    /**
     * Batch-validates the last N chained rows in one pass, so a dashboard
     * can show chain health on mount instead of forcing per-row verify
     * clicks. n is clamped to [1, 500].
     */
    public AuditChainHealthResponse chainHealth(int requestedWindow) {
        int n = Math.max(1, Math.min(requestedWindow, 500));

        // The window is the last n rows by id; each row's link is then looked
        // up by hash, wherever its predecessor sits (see linked()). The window
        // joins back to the base table because the canonical string needs
        // real column values, while the ordering/limit subquery only needs
        // id. The link counts come from two semi-joins over audit_logs, once
        // each, rather than two lookups per row.
        String windowSql = "WITH win AS ("
                + "  SELECT a.id, a.prev_hash, a.row_hash, "
                + "  LOWER(RAWTOHEX(STANDARD_HASH(" + canonicalCall("a") + ", 'SHA256'))) AS recomputed "
                + "  FROM audit_logs a JOIN ("
                + "    SELECT id FROM audit_logs WHERE row_hash IS NOT NULL "
                + "    ORDER BY id DESC FETCH FIRST ? ROWS ONLY"
                + "  ) recent ON recent.id = a.id"
                + "), links AS ("
                + "  SELECT link_hash, SUM(named) AS predecessors, SUM(naming) AS successors FROM ("
                + "    SELECT row_hash AS link_hash, 1 AS named, 0 AS naming FROM audit_logs "
                + "    WHERE row_hash IN (SELECT prev_hash FROM win) "
                + "    UNION ALL "
                + "    SELECT prev_hash, 0, 1 FROM audit_logs WHERE prev_hash IN (SELECT prev_hash FROM win)"
                + "  ) GROUP BY link_hash"
                + ") "
                + "SELECT w.id, w.prev_hash, w.row_hash, w.recomputed, "
                + "NVL(l.predecessors, 0) AS predecessors, NVL(l.successors, 0) AS successors "
                + "FROM win w LEFT JOIN links l ON l.link_hash = w.prev_hash "
                + "ORDER BY w.id";

        List<Map<String, Object>> windowRows = jdbcTemplate.queryForList(windowSql, n);

        long unchainedTotal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE row_hash IS NULL", Long.class);

        // Asked only when a row in the window names no predecessor.
        boolean claimsGenesis = windowRows.stream().anyMatch(row -> row.get("prev_hash") == null);
        long genesisRows = claimsGenesis ? genesisRows() : 0;

        int hashMismatches = 0;
        int linkBreaks = 0;
        List<Long> badIds = new ArrayList<>();
        for (Map<String, Object> row : windowRows) {
            long id = ((Number) row.get("id")).longValue();
            String rowHash = (String) row.get("row_hash");
            String recomputed = (String) row.get("recomputed");
            String prevHash = (String) row.get("prev_hash");
            boolean bad = false;
            if (!rowHash.equals(recomputed)) {
                hashMismatches++;
                bad = true;
            }
            if (!linked(prevHash, count(row, "predecessors"), count(row, "successors"), genesisRows)) {
                linkBreaks++;
                bad = true;
            }
            if (bad && badIds.size() < 10) {
                badIds.add(id);
            }
        }

        String status = (hashMismatches == 0 && linkBreaks == 0) ? "ok" : "tampered";
        return new AuditChainHealthResponse(
                status, windowRows.size(), n, hashMismatches, linkBreaks, badIds, unchainedTotal);
    }

    private long genesisRows() {
        return jdbcTemplate.queryForObject(GENESIS_ROWS_SQL, Long.class);
    }

    private static long count(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }
}
