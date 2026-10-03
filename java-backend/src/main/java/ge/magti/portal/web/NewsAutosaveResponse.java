package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.News;

import java.time.OffsetDateTime;

/**
 * The news autosave response -- built from the just-updated row, not
 * the request payload.
 */
public record NewsAutosaveResponse(
        Long id,
        String title,
        String content,
        @JsonProperty("target_department") String targetDepartment,
        @JsonProperty("attachment_url") String attachmentUrl,
        @JsonProperty("visible_to_tech_info") boolean visibleToTechInfo,
        @JsonProperty("visible_to_service_center") boolean visibleToServiceCenter,
        @JsonProperty("expires_at") OffsetDateTime expiresAt,
        @JsonProperty("is_draft") boolean isDraft,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        int version
) {
    public static NewsAutosaveResponse from(News news) {
        return new NewsAutosaveResponse(news.getId(), news.getTitle(), news.getContent(), news.getTargetDepartment(),
                news.getAttachmentUrl(), news.isVisibleToTechInfo(), news.isVisibleToServiceCenter(),
                news.getExpiresAt(), news.isDraft(), news.getCreatedAt(), news.getVersion());
    }
}
