package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors schemas.py's ArticleReadReceiptRow. read_at/deadline are pre-formatted strings (TbilisiTime.format), not raw datetimes -- see get_article_read_receipts. */
public record ArticleReadReceiptRowResponse(
        @JsonProperty("operator_id") Long operatorId,
        @JsonProperty("operator_name") String operatorName,
        @JsonProperty("operator_email") String operatorEmail,
        String department,
        @JsonProperty("read_at") String readAt,
        @JsonProperty("article_version") Integer articleVersion,
        @JsonProperty("has_read") boolean hasRead,
        @JsonProperty("is_late") boolean isLate,
        String deadline,
        String status
) {
}
