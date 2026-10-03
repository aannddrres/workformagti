package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

public record RecentlyViewedItemResponse(
        @JsonProperty("article_id") Long articleId,
        String title,
        @JsonProperty("viewed_at") OffsetDateTime viewedAt
) {
}
