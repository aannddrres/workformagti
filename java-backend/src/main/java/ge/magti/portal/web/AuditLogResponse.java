package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Mirrors schemas.py's AuditLogResponse -- one row of GET /api/audit-logs,
 * and (in reduced form) the source data for GET /api/audit-logs/export.
 * {@code adminName}/{@code itemName} are already resolved (snapshot-or-live
 * fallback, or the "deleted user" label) by the time a row reaches this
 * record -- see AuditLogQueryService's row mapper, mirroring
 * routers/audit_logs.py's get_audit_logs result-dict comprehension
 * (:216-234) and its _audit_item_name helper (:71-78).
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
