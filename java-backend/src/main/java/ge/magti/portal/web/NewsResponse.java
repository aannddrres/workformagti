package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.News;

import java.time.OffsetDateTime;

/** Mirrors schemas.py's NewsResponse. */
public record NewsResponse(
        Long id,
        String title,
        String content,
        @JsonProperty("target_department") String targetDepartment,
        @JsonProperty("attachment_url") String attachmentUrl,
        @JsonProperty("visible_to_tech_info") boolean visibleToTechInfo,
        @JsonProperty("visible_to_service_center") boolean visibleToServiceCenter,
        @JsonProperty("expires_at") OffsetDateTime expiresAt,
        @JsonProperty("is_draft") boolean isDraft,
        @JsonProperty("author_id") Long authorId,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        int version,
        @JsonProperty("is_archived") boolean isArchived
) {
    public static NewsResponse from(News news) {
        return new NewsResponse(news.getId(), news.getTitle(), news.getContent(), news.getTargetDepartment(),
                news.getAttachmentUrl(), news.isVisibleToTechInfo(), news.isVisibleToServiceCenter(),
                news.getExpiresAt(), news.isDraft(), news.getAuthorId(), news.getCreatedAt(), news.getVersion(),
                news.isArchived());
    }
}
