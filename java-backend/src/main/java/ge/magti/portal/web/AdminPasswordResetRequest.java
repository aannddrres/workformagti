package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/** Mirrors schemas.py's AdminPasswordResetRequest (schemas.py:556-558). */
public record AdminPasswordResetRequest(@JsonProperty("new_password") @NotBlank String newPassword) {
}
