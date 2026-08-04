package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors get_article_views' per-row ad-hoc dict shape (routers/articles.py:1349-1357). viewed_at is a pre-formatted string (TbilisiTime.format), not a raw datetime. */
public record ArticleViewRowResponse(
        @JsonProperty("operator_id") Long operatorId,
        @JsonProperty("operator_name") String operatorName,
        @JsonProperty("operator_email") String operatorEmail,
        String department,
        @JsonProperty("article_version") int articleVersion,
        @JsonProperty("viewed_at") String viewedAt
) {
}
