package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/** Mirrors get_stale_articles' ad-hoc dict shape (routers/articles.py:1549-1557) -- no formal Pydantic schema in Python either. */
public record StaleArticleResponse(
        Long id,
        String title,
        @JsonProperty("target_departments") List<String> targetDepartments,
        @JsonProperty("last_verified_at") OffsetDateTime lastVerifiedAt,
        @JsonProperty("days_stale") long daysStale
) {
}
