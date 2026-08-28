package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.article.ArticleListItem;
import ge.magti.portal.domain.Article;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Mirrors schemas.py's ArticleSummaryResponse -- the list-view shape
 * (excludes {@code content}, adds {@code category_name}).
 *
 * <p>The Java list query uses a CLOB-free projection. V45 maintains the real
 * read-time estimate as a derived Oracle scalar, so summaries remain accurate
 * without materializing {@code content}.
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
    public static ArticleSummaryResponse from(
            ArticleListItem article, String categoryName, List<String> targetDepartments) {
        return new ArticleSummaryResponse(
                article.id(), article.title(), article.categoryId(), categoryName, article.tags(),
                targetDepartments, article.status() == null ? "draft" : article.status(),
                article.publishedAt(), article.createdAt(), article.readTime(), article.audienceProfile(),
                article.visibleToTechInfo(), article.visibleToServiceCenter(), article.draft());
    }

    /** Full-entity mapper retained for non-list call sites such as global search. */
    public static ArticleSummaryResponse from(Article article, String categoryName, List<String> targetDepartments) {
        return new ArticleSummaryResponse(
                article.getId(), article.getTitle(), article.getCategoryId(), categoryName, article.getTags(),
                targetDepartments, article.getStatus() == null ? "draft" : article.getStatus(),
                article.getPublishedAt(), article.getCreatedAt(),
                ArticleResponse.computeReadTime(article.getContent()), article.getAudienceProfile(),
                article.isVisibleToTechInfo(), article.isVisibleToServiceCenter(), article.isDraft());
    }
}
