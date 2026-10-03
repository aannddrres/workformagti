package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One row of {@link ComplianceStatsResponse#topArticles()}.
 *
 * <p>Deliberate fix vs. the original app: its top-5-most-read chart had no
 * {@code read_count} field to show, so its frontend filled
 * in synthetic descending placeholders (95, 80, 65, ...) instead of a real
 * count. The real count was always computed server-side --
 * {@link ge.magti.portal.repository.RequiredReadingRepository#topReadArticleIds}
 * already returns it as the second column of each row -- just never sent to
 * the client. This record carries it through instead of reusing the full
 * {@link ArticleResponse} shape, which has no such field.
 */
public record TopArticleResponse(Long id, String title, @JsonProperty("read_count") long readCount) {
}
