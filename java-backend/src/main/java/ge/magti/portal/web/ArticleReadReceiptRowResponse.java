package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** read_at/deadline are pre-formatted strings (TbilisiTime.format), not raw datetimes. */
public record ArticleReadReceiptRowResponse(
        @JsonProperty("operator_id") Long operatorId,
        @JsonProperty("operator_name") String operatorName,
        String department,
        @JsonProperty("read_at") String readAt,
        @JsonProperty("article_version") Integer articleVersion,
        @JsonProperty("has_read") boolean hasRead,
        @JsonProperty("is_late") boolean isLate,
        String deadline,
        String status
) {
}
