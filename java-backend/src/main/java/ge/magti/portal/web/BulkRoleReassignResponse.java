package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors bulk_reassign_roles' plain-dict response (routers/users.py:179-184). */
public record BulkRoleReassignResponse(
        @JsonProperty("new_role") String newRole,
        int changed,
        int skipped,
        int requested
) {
}
