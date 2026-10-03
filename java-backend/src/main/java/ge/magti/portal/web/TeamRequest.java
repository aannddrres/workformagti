package ge.magti.portal.web;

import jakarta.validation.constraints.NotBlank;

public record TeamRequest(@NotBlank String name) {
}
