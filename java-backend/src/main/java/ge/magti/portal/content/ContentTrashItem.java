package ge.magti.portal.content;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** One recoverable payload in the shared content-management trash view. */
public record ContentTrashItem(
        @JsonProperty("item_type") String itemType,
        @JsonProperty("item_id") Long itemId,
        String title,
        @JsonProperty("trashed_at") OffsetDateTime trashedAt,
        @JsonProperty("purge_after") OffsetDateTime purgeAfter,
        @JsonProperty("trashed_by") Long trashedBy,
        @JsonProperty("trashed_by_name") String trashedByName,
        @JsonProperty("legal_hold") boolean legalHold) {
}
