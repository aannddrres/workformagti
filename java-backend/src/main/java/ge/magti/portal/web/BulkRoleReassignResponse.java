package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BulkRoleReassignResponse(
        @JsonProperty("new_role") String newRole,
        int changed,
        int skipped,
        int requested
) {
}
