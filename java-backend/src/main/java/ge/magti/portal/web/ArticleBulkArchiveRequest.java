package ge.magti.portal.web;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ArticleBulkArchiveRequest(
        @NotNull List<Long> ids,
        @NotNull Boolean archive
) {
}
