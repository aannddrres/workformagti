package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** Mirrors get_article_history's ad-hoc dict shape (routers/articles.py:556-564) -- no formal Pydantic schema in Python either. */
public record ArticleHistoryItemResponse(
        Long id,
        String title,
        String content,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("author_name") String authorName,
        @JsonProperty("version_id") Integer versionId
) {
}
