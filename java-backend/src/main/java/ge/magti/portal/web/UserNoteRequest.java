package ge.magti.portal.web;

import jakarta.validation.constraints.NotNull;

/** Mirrors schemas.py's UserNoteCreate. */
public record UserNoteRequest(@NotNull String content) {
}
