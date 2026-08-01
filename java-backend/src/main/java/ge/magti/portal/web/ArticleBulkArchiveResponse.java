package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Mirrors schemas.py's ArticleBulkArchiveResponse. */
public record ArticleBulkArchiveResponse(
        int updated,
        String status,
        @JsonProperty("skipped_ids") List<Long> skippedIds
) {
}
