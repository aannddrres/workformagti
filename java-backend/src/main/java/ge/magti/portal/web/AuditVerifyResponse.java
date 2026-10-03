package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response shape for
 * GET /api/audit-logs/{id}/verify. status is "ok" | "tampered" | "unchained".
 */
public record AuditVerifyResponse(
        String status,
        @JsonProperty("hash_match") Boolean hashMatch,
        @JsonProperty("chain_match") Boolean chainMatch,
        @JsonProperty("row_hash") String rowHash,
        @JsonProperty("recomputed_hash") String recomputedHash
) {
    public static AuditVerifyResponse unchained() {
        return new AuditVerifyResponse("unchained", null, null, null, null);
    }

    public static AuditVerifyResponse of(boolean hashMatch, boolean chainMatch, String rowHash, String recomputedHash) {
        return new AuditVerifyResponse(
                hashMatch && chainMatch ? "ok" : "tampered", hashMatch, chainMatch, rowHash, recomputedHash);
    }
}
