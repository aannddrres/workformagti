package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

public record ArticleVersionItemResponse(
        int version,
        String title,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("author_name") String authorName,
        @JsonProperty("history_id") Long historyId
) {
}
