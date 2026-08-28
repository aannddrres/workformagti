package ge.magti.portal.article;

import java.time.OffsetDateTime;

/** CLOB-free article revision metadata for history/version list screens. */
public record ArticleHistorySummary(
        Long id,
        String title,
        OffsetDateTime updatedAt,
        Long updatedBy,
        Integer versionId
) {
}
