package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** One entry of the notifications-summary unread/overdue reading list. */
public record UnreadReadingSummaryItem(
        Long id,
        @JsonProperty("item_type") String itemType,
        @JsonProperty("item_id") Long itemId,
        String title,
        @JsonProperty("due_date") OffsetDateTime dueDate,
        @JsonProperty("is_overdue") boolean isOverdue
) {
}
