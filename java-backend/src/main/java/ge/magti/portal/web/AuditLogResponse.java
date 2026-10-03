package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * One row of GET /api/audit-logs,
 * and (in reduced form) the source data for GET /api/audit-logs/export.
 * {@code adminName}/{@code itemName} are already resolved (snapshot-or-live
 * fallback, or the "deleted user" label) by the time a row reaches this
 * record -- see AuditLogQueryService's row mapper.
 */
public record AuditLogResponse(
        Long id,
        @JsonProperty("admin_id") Long adminId,
        @JsonProperty("admin_name") String adminName,
        String action,
        @JsonProperty("item_type") String itemType,
        @JsonProperty("item_id") Long itemId,
        @JsonProperty("item_name") String itemName,
        OffsetDateTime timestamp,
        String category,
        String details,
        @JsonProperty("prev_hash") String prevHash,
        @JsonProperty("row_hash") String rowHash,
        @JsonProperty("ip_address") String ipAddress,
        @JsonProperty("user_agent") String userAgent
) {
}
