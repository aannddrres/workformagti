package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** The caller's own read-receipt status for an article -- raw datetime, no TbilisiTime.format. */
public record MyReadReceiptStatusResponse(
        @JsonProperty("has_read") boolean hasRead,
        @JsonProperty("read_at") OffsetDateTime readAt,
        @JsonProperty("article_version") Integer articleVersion,
        @JsonProperty("current_version") int currentVersion
) {
}
