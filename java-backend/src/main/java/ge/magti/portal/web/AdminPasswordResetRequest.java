package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record AdminPasswordResetRequest(@JsonProperty("new_password") @NotBlank String newPassword) {
}
