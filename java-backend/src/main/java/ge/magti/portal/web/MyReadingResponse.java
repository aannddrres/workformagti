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
        @JsonProperty("item_content") String itemContent,
        /**
         * The material was edited after this operator acknowledged it.
         *
         * <p>The acknowledgement itself deliberately stays valid -- it is a
         * record that someone read a document on a date, and deleting it
         * would destroy the evidence the whole compliance story rests on.
         * But leaving it silent overstated coverage: a procedure could
         * change and the manager's dashboard would still count everyone as
         * up to date on it.
         *
         * <p>Found by UAT (F-2), which noticed the system was already
         * version-aware in one half of the mechanism and not the other: a
         * *new* acknowledgement on an edited article requires passing its
         * quiz again, while an *existing* one kept counting with nothing to
         * show it was made against older text. Both timestamps were already
         * stored; nothing compared them.
         */
        @JsonProperty("changed_since_read") boolean changedSinceRead
) {
}
