package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Mirrors schemas.py's VideoInstructionCreate/VideoInstructionBase --
 * used for both create and update, exactly as Python does (one shared
 * request schema for both routes).
 */
public record VideoInstructionRequest(
        @NotBlank String title,
        @NotBlank @JsonProperty("video_url") String videoUrl,
        String category,
        @JsonProperty("target_department") String targetDepartment,
        String tags
) {
    public String targetDepartmentOrDefault() {
        return (targetDepartment == null || targetDepartment.isBlank()) ? "All" : targetDepartment;
    }
}
