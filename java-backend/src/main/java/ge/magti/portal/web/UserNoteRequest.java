package ge.magti.portal.web;

import jakarta.validation.constraints.NotNull;

public record UserNoteRequest(@NotNull String content) {
}
