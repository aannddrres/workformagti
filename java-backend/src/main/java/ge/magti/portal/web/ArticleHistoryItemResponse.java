package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

public record ArticleHistoryItemResponse(
        Long id,
        String title,
        String content,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("author_name") String authorName,
        @JsonProperty("version_id") Integer versionId
) {
}
