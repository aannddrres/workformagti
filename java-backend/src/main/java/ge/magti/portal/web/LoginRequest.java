package ge.magti.portal.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Mirrors schemas.py's LoginRequest (email: EmailStr, password: str). */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password) {
}
