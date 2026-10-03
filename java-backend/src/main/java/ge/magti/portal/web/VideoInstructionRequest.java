package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Mirrors schemas.py's VideoInstructionCreate/VideoInstructionBase --
 * used for both create and update, exactly as Python does (one shared
 * request schema for both routes).
 */
public record VideoInstructionRequest(
        @NotBlank @Size(max = 500) String title,
        // A web address, a YouTube link or id (what the player accepts), or an
        // uploaded file. "javascript:alert(1)" and plain
        // text were saved, and readers got an empty player with no message
        // (simulation, 2026-10-01).
        @NotBlank @Size(max = 1000)
        @Pattern(regexp = "(https?://\\S+|(www\\.)?(youtube\\.com|youtu\\.be)/\\S+|[A-Za-z0-9_-]{11}|/uploads/[A-Za-z0-9._-]+)",
                message = "ვიდეოს ბმული უნდა იყოს http(s) მისამართი, YouTube-ის ბმული ან id, ან ატვირთული ფაილი")
        @JsonProperty("video_url") String videoUrl,
        @Size(max = 200) String category,
        @Size(max = 200) @JsonProperty("target_department") String targetDepartment,
        @Size(max = 500) String tags
) {
    public String targetDepartmentOrDefault() {
        return (targetDepartment == null || targetDepartment.isBlank()) ? "All" : targetDepartment;
    }
}
