package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.Article;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The autosave response. The autosave *request* is a partial update, but
 * the response is built straight off the just-updated row, not the request
 * payload -- so every field here reflects the article's current state, not just
 * whatever the client happened to send this autosave.
 */
public record ArticleAutosaveResponse(
        Long id,
        String title,
        String content,
        @JsonProperty("category_id") Long categoryId,
        String tags,
        @JsonProperty("target_departments") List<String> targetDepartments,
        String status,
        @JsonProperty("published_at") OffsetDateTime publishedAt,
        @JsonProperty("attachment_url") String attachmentUrl,
        @JsonProperty("audience_profile") String audienceProfile,
        @JsonProperty("visible_to_tech_info") boolean visibleToTechInfo,
        @JsonProperty("visible_to_service_center") boolean visibleToServiceCenter,
        @JsonProperty("is_draft") boolean isDraft,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        int version
) {
    public static ArticleAutosaveResponse from(Article article, List<String> targetDepartments) {
        return new ArticleAutosaveResponse(
                article.getId(), article.getTitle(), article.getContent(), article.getCategoryId(),
                article.getTags(), targetDepartments,
                article.getStatus() == null ? "draft" : article.getStatus(),
                article.getPublishedAt(), article.getAttachmentUrl(), article.getAudienceProfile(),
                article.isVisibleToTechInfo(), article.isVisibleToServiceCenter(), article.isDraft(),
                article.getCreatedAt(), article.getUpdatedAt(), article.getVersion());
    }
}
