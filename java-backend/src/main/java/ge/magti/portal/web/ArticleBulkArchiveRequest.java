package ge.magti.portal.web;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Mirrors schemas.py's ArticleBulkArchiveRequest. */
public record ArticleBulkArchiveRequest(
        @NotNull List<Long> ids,
        @NotNull Boolean archive
) {
}
