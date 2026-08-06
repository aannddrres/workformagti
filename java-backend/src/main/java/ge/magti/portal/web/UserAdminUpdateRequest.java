package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/** Mirrors schemas.py's UserAdminUpdate (schemas.py:511-517). */
public record UserAdminUpdateRequest(
        @NotBlank String role,
        String department,
        String phone,
        String position,
        @JsonProperty("team_id") Long teamId
) {
}
