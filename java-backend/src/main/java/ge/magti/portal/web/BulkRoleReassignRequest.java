package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record BulkRoleReassignRequest(
        @JsonProperty("user_ids") @NotNull List<Long> userIds,
        @JsonProperty("new_role") String newRole
) {
}
