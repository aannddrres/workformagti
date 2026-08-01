package ge.magti.portal.audit;

import ge.magti.portal.web.AuditChainHealthResponse;
import ge.magti.portal.web.AuditVerifyResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
                + "prev.row_hash AS actual_prev_row_hash "
                + "FROM audit_logs al "
                + "LEFT JOIN audit_logs prev ON prev.id = ("
                + "  SELECT id FROM audit_logs WHERE id < al.id AND row_hash IS NOT NULL "
                + "  ORDER BY id DESC FETCH FIRST 1 ROW ONLY"
                + ") "
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
        String actualPrevRowHash = (String) row.get("actual_prev_row_hash");

        boolean hashMatch = rowHash.equals(recomputedHash);
        // Re-derives the expected predecessor live rather than trusting the
        // stored prev_hash blindly, so an altered/deleted predecessor is
        // caught too, not just a directly-altered row.
        boolean chainMatch = Objects.equals(prevHash, actualPrevRowHash);
        return Optional.of(AuditVerifyResponse.of(hashMatch, chainMatch, rowHash, recomputedHash));
    }

    /**
     * Batch-validates the last N chained rows in one pass, so a dashboard
     * can show chain health on mount instead of forcing per-row verify
     * clicks. n is clamped to [1, 500].
     */
    public AuditChainHealthResponse chainHealth(int requestedWindow) {
        int n = Math.max(1, Math.min(requestedWindow, 500));

        // JOINs back to the base table rather than selecting from the CTE
        // directly: audit_logs_canonical_string needs real column values,
        // and the ordering/limit subquery only needs to select id.
        String windowSql = "SELECT a.id, a.prev_hash, a.row_hash, "
                + "LOWER(RAWTOHEX(STANDARD_HASH(" + canonicalCall("a") + ", 'SHA256'))) AS recomputed "
                + "FROM audit_logs a JOIN ("
                + "  SELECT id FROM audit_logs WHERE row_hash IS NOT NULL "
                + "  ORDER BY id DESC FETCH FIRST ? ROWS ONLY"
                + ") recent ON recent.id = a.id "
                + "ORDER BY a.id";

        List<Map<String, Object>> windowRows = jdbcTemplate.queryForList(windowSql, n);

        long unchainedTotal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE row_hash IS NULL", Long.class);

        // Boundary predecessor: the chained row just before the window, so
        // the window's oldest row gets a real link check instead of a
        // skipped one (this also catches a fake "second genesis" inside
        // the window).
        String expectedPrev = null;
        if (!windowRows.isEmpty()) {
            long minId = ((Number) windowRows.get(0).get("id")).longValue();
            expectedPrev = jdbcTemplate.query(
                    "SELECT row_hash FROM audit_logs WHERE row_hash IS NOT NULL AND id < ? "
                            + "ORDER BY id DESC FETCH FIRST 1 ROW ONLY",
                    rs -> rs.next() ? rs.getString(1) : null,
                    minId);
        }

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
            if (!Objects.equals(prevHash, expectedPrev)) {
                linkBreaks++;
                bad = true;
            }
            if (bad && badIds.size() < 10) {
                badIds.add(id);
            }
            expectedPrev = rowHash;
        }

        String status = (hashMismatches == 0 && linkBreaks == 0) ? "ok" : "tampered";
        return new AuditChainHealthResponse(
                status, windowRows.size(), n, hashMismatches, linkBreaks, badIds, unchainedTotal);
    }
}
