package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * What a bulk operation actually did.
 *
 * <p>{@code skipped_ids} is not an error list. A bulk call is partial by
 * nature -- an id that no longer exists, or a row already in the requested
 * state -- and answering the whole request with a failure because one of a
 * hundred rows was already correct would make the operation unusable on the
 * batch it exists for. The caller is told which ones did not move so it can
 * say so, rather than being left to diff the list itself.
 */
public record ArticleBulkResponse(
        int updated,
        @JsonProperty("skipped_ids") List<Long> skippedIds
) {
}
