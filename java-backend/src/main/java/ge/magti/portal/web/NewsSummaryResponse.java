package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.News;

import java.time.OffsetDateTime;

/** Mirrors schemas.py's NewsSummaryResponse -- the list-view shape (excludes content). */
public record NewsSummaryResponse(
        Long id,
        String title,
        @JsonProperty("target_department") String targetDepartment,
        @JsonProperty("attachment_url") String attachmentUrl,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        int version,
        @JsonProperty("visible_to_tech_info") boolean visibleToTechInfo,
        @JsonProperty("visible_to_service_center") boolean visibleToServiceCenter,
        @JsonProperty("is_archived") boolean isArchived,
        @JsonProperty("expires_at") OffsetDateTime expiresAt,
        @JsonProperty("is_draft") boolean isDraft,
        @JsonProperty("author_id") Long authorId
) {
    public static NewsSummaryResponse from(News news) {
        return new NewsSummaryResponse(news.getId(), news.getTitle(), news.getTargetDepartment(),
                news.getAttachmentUrl(), news.getCreatedAt(), news.getVersion(), news.isVisibleToTechInfo(),
                news.isVisibleToServiceCenter(), news.isArchived(), news.getExpiresAt(), news.isDraft(),
                news.getAuthorId());
    }
}
