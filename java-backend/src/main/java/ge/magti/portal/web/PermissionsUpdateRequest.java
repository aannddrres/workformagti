package ge.magti.portal.web;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Mirrors schemas.py's PermissionsUpdateRequest (schemas.py:561-563). */
public record PermissionsUpdateRequest(@NotNull List<String> permissions) {
}
