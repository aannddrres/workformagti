package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** viewed_at is a pre-formatted string (TbilisiTime.format), not a raw datetime. */
public record ArticleViewRowResponse(
        @JsonProperty("operator_id") Long operatorId,
        @JsonProperty("operator_name") String operatorName,
        @JsonProperty("operator_email") String operatorEmail,
        String department,
        @JsonProperty("article_version") int articleVersion,
        @JsonProperty("viewed_at") String viewedAt
) {
}
