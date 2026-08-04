package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** Mirrors get_my_article_read_receipt_status's ad-hoc dict (routers/articles.py:1259-1271) -- raw datetime, no response_model. */
public record MyReadReceiptStatusResponse(
        @JsonProperty("has_read") boolean hasRead,
        @JsonProperty("read_at") OffsetDateTime readAt,
        @JsonProperty("article_version") Integer articleVersion,
        @JsonProperty("current_version") int currentVersion
) {
}
