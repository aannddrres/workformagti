package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record PasswordChangeRequest(
        @JsonProperty("current_password") @NotBlank String currentPassword,
        @JsonProperty("new_password") @NotBlank String newPassword
) {
}
