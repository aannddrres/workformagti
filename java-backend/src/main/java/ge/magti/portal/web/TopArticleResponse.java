package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One row of {@link ComplianceStatsResponse#topArticles()}.
 *
 * <p>Deliberate fix vs. the Python original (routers/stats.py:175-224,
 * schemas.ArticleResponse): Python's top-5-most-read chart has no
 * {@code read_count} field to show, so its frontend (frontend_api.js) fills
 * in synthetic descending placeholders (95, 80, 65, ...) instead of a real
 * count. The real count was always computed server-side --
 * {@link ge.magti.portal.repository.RequiredReadingRepository#topReadArticleIds}
 * already returns it as the second column of each row -- just never sent to
 * the client. This record carries it through instead of reusing the full
 * {@link ArticleResponse} shape, which has no such field.
 */
public record TopArticleResponse(Long id, String title, @JsonProperty("read_count") long readCount) {
}
