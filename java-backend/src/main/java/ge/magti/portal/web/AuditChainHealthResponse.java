package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response shape for
 * GET /api/audit-logs/chain-health. status is "ok" | "tampered" (there is
 * no "unavailable" case: this backend only ever targets Oracle, so the
 * chain always exists).
 */
public record AuditChainHealthResponse(
        String status,
        int checked,
        int window,
        @JsonProperty("hash_mismatches") int hashMismatches,
        @JsonProperty("link_breaks") int linkBreaks,
        @JsonProperty("bad_ids") List<Long> badIds,
        @JsonProperty("unchained_total") long unchainedTotal,
        @JsonProperty("tail_state_mismatch") boolean tailStateMismatch
) {
}
