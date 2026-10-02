package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Admin profile update. When overrides is present, lockVersion is required and
 * profile, role and permission deltas commit atomically.
 */
public record UserAdminUpdateRequest(
        @NotBlank String role,
        // Column limits: past them Oracle refused the update as a 500 (2026-10-02).
        @Size(max = 200, message = "დეპარტამენტი 200 სიმბოლოზე გრძელი ვერ იქნება") String department,
        @Size(max = 30, message = "ტელეფონი 30 სიმბოლოზე გრძელი ვერ იქნება") String phone,
        @Size(max = 200, message = "პოზიცია 200 სიმბოლოზე გრძელი ვერ იქნება") String position,
        @JsonProperty("team_id") Long teamId,
        @JsonProperty("lock_version") @PositiveOrZero Long lockVersion,
        List<@NotNull @Valid PermissionOverrideDelta> overrides
) {
}
