package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** Mirrors create_article_read_receipt's ad-hoc dict (routers/articles.py:1235-1239) -- raw datetime, no response_model, so no TbilisiTime.format here unlike the admin list endpoints. */
public record CreateReadReceiptResponse(
        String status,
        @JsonProperty("read_at") OffsetDateTime readAt,
        @JsonProperty("article_version") int articleVersion
) {
}
