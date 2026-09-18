package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * The accounts an administrator has picked to switch off in one decision
 * (PO-24). Deliberately carries no "active" flag: this is the leaver sweep,
 * and re-activating a batch of people is not a thing anybody asked for --
 * re-activation stays one row at a time, where it is a considered act.
 */
public record BulkDeactivateRequest(
        @JsonProperty("user_ids") @NotNull List<Long> userIds
) {
}
