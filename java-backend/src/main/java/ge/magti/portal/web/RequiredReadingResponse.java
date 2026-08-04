package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.RequiredReading;

import java.time.OffsetDateTime;

/** Mirrors schemas.py's RequiredReadingResponse (= RequiredReadingBase + id). */
public record RequiredReadingResponse(
        Long id,
        @JsonProperty("item_type") String itemType,
        @JsonProperty("item_id") Long itemId,
        @JsonProperty("target_department") String targetDepartment,
        @JsonProperty("due_date") OffsetDateTime dueDate,
        String priority
) {
    public static RequiredReadingResponse from(RequiredReading reading) {
        return new RequiredReadingResponse(reading.getId(), reading.getItemType(), reading.getItemId(),
                reading.getTargetDepartment(), reading.getDueDate(), reading.getPriority());
    }
}
