package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One batch of the whole-ledger check, GET /api/audit-logs/chain-health/full.
 * {@code checked} rows were examined in this batch, out of {@code total}
 * chained rows; {@code next_after_id} is where the next batch starts, null
 * once the last one has run. {@code bad_ids} names at most ten rows of this
 * batch; the two counts are complete.
 */
public record AuditChainFullCheckResponse(
        String status,
        int checked,
        long total,
        @JsonProperty("hash_mismatches") int hashMismatches,
        @JsonProperty("link_breaks") int linkBreaks,
        @JsonProperty("bad_ids") List<Long> badIds,
        @JsonProperty("tail_state_mismatch") boolean tailStateMismatch,
        @JsonProperty("next_after_id") Long nextAfterId
) {
}
