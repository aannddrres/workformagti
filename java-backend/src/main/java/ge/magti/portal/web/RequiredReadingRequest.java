package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/** Mirrors schemas.py's RequiredReadingBase. */
public record RequiredReadingRequest(
        @NotBlank @JsonProperty("item_type") String itemType,
        @NotNull @JsonProperty("item_id") Long itemId,
        @JsonProperty("target_department") String targetDepartment,
        @NotNull @JsonProperty("due_date") OffsetDateTime dueDate,
        String priority
) {
    public String targetDepartmentOrDefault() {
        return (targetDepartment == null || targetDepartment.isBlank()) ? "All" : targetDepartment;
    }

    public String priorityOrDefault() {
        return (priority == null || priority.isBlank()) ? "normal" : priority;
    }
}
