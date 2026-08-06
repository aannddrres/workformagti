package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Mirrors schemas.py's UserCreateAdmin (schemas.py:525-536). */
public record UserCreateAdminRequest(
        @Email @NotBlank String email,
        @NotBlank String name,
        String department,
        String position,
        String phone,
        String role,
        @NotBlank String password,
        @JsonProperty("team_id") Long teamId
) {
    public String roleOrDefault() {
        return (role == null || role.isBlank()) ? "operator" : role;
    }
}
