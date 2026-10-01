package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.Article;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Mirrors schemas.py's ArticleResponse (ArticleBase + id/created_at/
 * updated_at/version/read_time) field-for-field. Deliberately has no
 * category_name -- unlike {@link ArticleSummaryResponse}, ArticleResponse
 * never defines one in Python either.
 */
public record ArticleResponse(
        Long id,
        String title,
        String content,
        @JsonProperty("category_id") Long categoryId,
        String tags,
        @JsonProperty("target_departments") List<String> targetDepartments,
        String status,
        @JsonProperty("youtube_id") String youtubeId,
        @JsonProperty("published_at") OffsetDateTime publishedAt,
        @JsonProperty("attachment_url") String attachmentUrl,
        @JsonProperty("author_id") Long authorId,
        @JsonProperty("last_verified_at") OffsetDateTime lastVerifiedAt,
        @JsonProperty("audience_profile") String audienceProfile,
        @JsonProperty("visible_to_tech_info") boolean visibleToTechInfo,
        @JsonProperty("visible_to_service_center") boolean visibleToServiceCenter,
        @JsonProperty("is_draft") boolean isDraft,
        @JsonProperty("quiz_enabled") boolean quizEnabled,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        int version,
        @JsonProperty("read_time") int readTime,
        // What the editor sends back as ArticleRequest.lock_version, so a
        // save over someone else's newer one is refused, not silently lost.
        @JsonProperty("lock_version") int lockVersion
) {
    /** models.py's Article.read_time property (models.py:173-186). */
    public static int computeReadTime(String content) {
        if (content == null || content.isBlank()) {
            return 1;
        }
        int wordCount = content.trim().split("\\s+").length;
        return Math.max(1, wordCount / 150);
    }

    public static ArticleResponse from(Article article, List<String> targetDepartments) {
        return new ArticleResponse(
                article.getId(), article.getTitle(), article.getContent(), article.getCategoryId(),
                article.getTags(), targetDepartments,
                article.getStatus() == null ? "draft" : article.getStatus(),
                article.getYoutubeId() == null ? "" : article.getYoutubeId(),
                article.getPublishedAt(), article.getAttachmentUrl(), article.getAuthorId(),
                article.getLastVerifiedAt(), article.getAudienceProfile(),
                article.isVisibleToTechInfo(), article.isVisibleToServiceCenter(), article.isDraft(),
                article.isQuizEnabled(), article.getCreatedAt(), article.getUpdatedAt(), article.getVersion(),
                computeReadTime(article.getContent()), article.getLockVersion());
    }
}
