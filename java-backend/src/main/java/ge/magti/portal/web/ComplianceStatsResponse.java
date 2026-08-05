package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Mirrors schemas.ComplianceStatsResponse (schemas.py:428-432). */
public record ComplianceStatsResponse(
        @JsonProperty("read_percentage") double readPercentage,
        @JsonProperty("unread_percentage") double unreadPercentage,
        @JsonProperty("top_articles") List<ArticleResponse> topArticles) {
}
