package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.Article;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Mirrors schemas.py's ArticleSummaryResponse -- the list-view shape
 * (excludes {@code content}, adds {@code category_name}).
 *
 * <p>{@code read_time} is hardcoded to 1 here, not computed from real
 * content: routers/articles.py's list query explicitly {@code defer()}s the
 * content column for list-view performance, and models.Article.read_time
 * (models.py:173-186) returns exactly 1 whenever content is unloaded -- so
 * Python's list view always shows "1 minute" regardless of true length. This
 * port fetches full entities for simplicity (no lazy-loading infrastructure
 * exists yet), but the summary mapper still hardcodes 1 to match Python's
 * actual observed list-view output, not "improve" on it.
 */
public record ArticleSummaryResponse(
        Long id,
        String title,
        @JsonProperty("category_id") Long categoryId,
        @JsonProperty("category_name") String categoryName,
        String tags,
        @JsonProperty("target_departments") List<String> targetDepartments,
        String status,
        @JsonProperty("published_at") OffsetDateTime publishedAt,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("read_time") int readTime,
        @JsonProperty("audience_profile") String audienceProfile,
        @JsonProperty("visible_to_tech_info") boolean visibleToTechInfo,
        @JsonProperty("visible_to_service_center") boolean visibleToServiceCenter,
        @JsonProperty("is_draft") boolean isDraft
) {
    public static ArticleSummaryResponse from(Article article, String categoryName, List<String> targetDepartments) {
        return new ArticleSummaryResponse(
                article.getId(), article.getTitle(), article.getCategoryId(), categoryName, article.getTags(),
                targetDepartments, article.getStatus() == null ? "draft" : article.getStatus(),
                article.getPublishedAt(), article.getCreatedAt(), 1, article.getAudienceProfile(),
                article.isVisibleToTechInfo(), article.isVisibleToServiceCenter(), article.isDraft());
    }
}
