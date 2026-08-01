package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** Mirrors get_article_versions' ad-hoc dict shape (routers/articles.py:960-966) -- no formal Pydantic schema in Python either. */
public record ArticleVersionItemResponse(
        int version,
        String title,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("author_name") String authorName,
        @JsonProperty("history_id") Long historyId
) {
}
