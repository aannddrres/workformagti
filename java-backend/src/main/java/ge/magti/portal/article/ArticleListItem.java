package ge.magti.portal.article;

import java.time.OffsetDateTime;

/**
 * CLOB-free projection for the article list endpoint.
 *
 * <p>{@code Article.content} is intentionally absent. The database-maintained
 * {@code readTime} scalar preserves the existing response contract without
 * transferring every selected content CLOB from Oracle.
 */
public record ArticleListItem(
        Long id,
        String title,
        Long categoryId,
        String tags,
        String status,
        OffsetDateTime publishedAt,
        OffsetDateTime createdAt,
        int readTime,
        String audienceProfile,
        boolean visibleToTechInfo,
        boolean visibleToServiceCenter,
        boolean draft
) {
}
