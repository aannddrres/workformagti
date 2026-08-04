package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** Mirrors schemas.py's RecentlyViewedItem. */
public record RecentlyViewedItemResponse(
        @JsonProperty("article_id") Long articleId,
        String title,
        @JsonProperty("viewed_at") OffsetDateTime viewedAt
) {
}
