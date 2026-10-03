package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

public record NewsHistoryResponse(
        Long id,
        String title,
        String content,
        @JsonProperty("attachment_url") String attachmentUrl,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("author_name") String authorName
) {
}
