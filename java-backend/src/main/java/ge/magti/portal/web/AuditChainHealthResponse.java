package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Mirrors schemas.py's AuditChainHealthResponse -- response shape for
 * GET /api/audit-logs/chain-health. status is "ok" | "tampered" (the
 * Python "unavailable" case doesn't apply here: this Java port only ever
 * targets Oracle, so the chain always exists).
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
