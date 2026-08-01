package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.VideoInstruction;

import java.time.OffsetDateTime;

/** Mirrors schemas.py's VideoInstructionResponse field-for-field. */
public record VideoInstructionResponse(
        Long id,
        String title,
        @JsonProperty("video_url") String videoUrl,
        String category,
        @JsonProperty("target_department") String targetDepartment,
        String tags,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("views_count") int viewsCount,
        @JsonProperty("is_archived") boolean archived
) {
    public static VideoInstructionResponse from(VideoInstruction video) {
        return new VideoInstructionResponse(
                video.getId(), video.getTitle(), video.getVideoUrl(), video.getCategory(),
                video.getTargetDepartment(), video.getTags(), video.getCreatedAt(),
                video.getViewsCount(), video.isArchived());
    }
}
