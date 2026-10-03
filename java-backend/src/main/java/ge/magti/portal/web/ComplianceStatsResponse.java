package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Compliance statistics. {@code topArticles} carries a real
 * {@link TopArticleResponse#readCount()} rather than a full
 * {@code ArticleResponse} shape -- see
 * {@link TopArticleResponse}'s javadoc for why.
 */
public record ComplianceStatsResponse(
        @JsonProperty("read_percentage") double readPercentage,
        @JsonProperty("unread_percentage") double unreadPercentage,
        @JsonProperty("top_articles") List<TopArticleResponse> topArticles) {
}
