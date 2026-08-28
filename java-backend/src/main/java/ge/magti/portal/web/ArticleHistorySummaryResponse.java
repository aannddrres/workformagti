package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** CLOB-free companion to the legacy full article-history response. */
public record ArticleHistorySummaryResponse(
        Long id,
        String title,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("author_name") String authorName,
        @JsonProperty("version_id") Integer versionId
) {
}
