package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * Phase 6 permission delta. INHERIT deletes the override row; only ALLOW and
 * DENY are durable states. lockVersion prevents two admin drawers from
 * silently overwriting one another.
 */
public record PermissionsUpdateRequest(
        @JsonProperty("lock_version") @PositiveOrZero long lockVersion,
        @NotNull List<@NotNull PermissionOverrideDelta> overrides) {
}
