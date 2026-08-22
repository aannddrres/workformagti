package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.Reminder;
import ge.magti.portal.domain.ReminderType;

import java.time.OffsetDateTime;

public record ReminderResponse(
        Long id,
        @JsonProperty("recipient_name") String recipientName,
        ReminderType type,
        String content,
        @JsonProperty("required_reading_id") Long requiredReadingId,
        @JsonProperty("item_type") String itemType,
        @JsonProperty("item_id") Long itemId,
        @JsonProperty("item_title") String itemTitle,
        @JsonProperty("due_at") OffsetDateTime dueAt,
        @JsonProperty("triggered_by_name") String triggeredByName,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("read_at") OffsetDateTime readAt,
        @JsonProperty("lock_version") long lockVersion) {

    public static ReminderResponse from(Reminder value) {
        return new ReminderResponse(
                value.getId(), value.getRecipientNameSnapshot(), value.getType(), value.getContentSnapshot(),
                value.getRequiredReadingId(), value.getItemTypeSnapshot(), value.getItemIdSnapshot(),
                value.getItemTitleSnapshot(), value.getDueAtSnapshot(), value.getTriggeredByNameSnapshot(),
                value.getCreatedAt(), value.getReadAt(), value.getLockVersion());
    }
}
