package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/** Mirrors schemas.py's RequiredReadingBase. */
public record RequiredReadingRequest(
        @NotBlank @JsonProperty("item_type") String itemType,
        @NotNull @JsonProperty("item_id") Long itemId,
        @Size(max = 200, message = "დეპარტამენტი 200 სიმბოლოზე გრძელი ვერ იქნება") @JsonProperty("target_department") String targetDepartment,
        @NotNull @JsonProperty("due_date") OffsetDateTime dueDate,
        @Size(max = 20, message = "პრიორიტეტი 20 სიმბოლოზე გრძელი ვერ იქნება") String priority
) {
    public String targetDepartmentOrDefault() {
        return (targetDepartment == null || targetDepartment.isBlank()) ? "All" : targetDepartment;
    }

    public String priorityOrDefault() {
        return (priority == null || priority.isBlank()) ? "normal" : priority;
    }
}
