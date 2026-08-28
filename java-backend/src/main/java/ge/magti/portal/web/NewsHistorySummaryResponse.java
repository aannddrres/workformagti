package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** CLOB-free companion to the legacy full news-history response. */
public record NewsHistorySummaryResponse(
        Long id,
        String title,
        @JsonProperty("attachment_url") String attachmentUrl,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("author_name") String authorName
) {
}
