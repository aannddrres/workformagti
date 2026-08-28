package ge.magti.portal.article;

import java.time.OffsetDateTime;

/** CLOB-free metadata row used by stale and related-article reference endpoints. */
public record ArticleReferenceItem(
        Long id,
        String title,
        Long categoryId,
        String tags,
        OffsetDateTime createdAt,
        OffsetDateTime lastVerifiedAt
) {
}
