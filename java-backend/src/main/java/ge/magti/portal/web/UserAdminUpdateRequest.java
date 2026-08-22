package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * Admin profile update. When overrides is present, lockVersion is required and
 * profile, role and permission deltas commit atomically.
 */
public record UserAdminUpdateRequest(
        @NotBlank String role,
        String department,
        String phone,
        String position,
        @JsonProperty("team_id") Long teamId,
        @JsonProperty("lock_version") @PositiveOrZero Long lockVersion,
        List<@NotNull @Valid PermissionOverrideDelta> overrides
) {
}
