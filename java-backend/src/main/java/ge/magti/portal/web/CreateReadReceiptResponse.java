package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** Raw datetime, so no TbilisiTime.format here, unlike the admin list endpoints. */
public record CreateReadReceiptResponse(
        String status,
        @JsonProperty("read_at") OffsetDateTime readAt,
        @JsonProperty("article_version") int articleVersion
) {
}
