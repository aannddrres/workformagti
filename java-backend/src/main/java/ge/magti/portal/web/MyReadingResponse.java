package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** Mirrors schemas.py's MyReadingResponse -- one item of an operator's reading-task list. */
public record MyReadingResponse(
        RequiredReadingResponse reading,
        String status,
        @JsonProperty("read_at") OffsetDateTime readAt,
        @JsonProperty("is_overdue") boolean isOverdue,
        @JsonProperty("item_title") String itemTitle,
        @JsonProperty("item_content") String itemContent
) {
}
