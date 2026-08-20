package ge.magti.portal.repository;

import java.time.OffsetDateTime;

/** Oracle-specific write fragment for race-safe article-history snapshots. */
public interface ArticleHistoryRepositoryCustom {

    void archiveIfMissing(
            Long articleId, String title, String content, Long updatedBy, Integer versionId,
            OffsetDateTime updatedAt);
}
