package ge.magti.portal.web;

import jakarta.validation.constraints.NotBlank;

/** Mirrors schemas.py's TeamCreate (schemas.py:647-650). */
public record TeamRequest(@NotBlank String name) {
}
