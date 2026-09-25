package ge.magti.portal.repository;

import ge.magti.portal.domain.ArticleReadReceipt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ArticleReadReceiptRepository extends JpaRepository<ArticleReadReceipt, Long> {

    /**
     * BL-12: filters on {@code article_id_snapshot}, not the foreign key.
     * Identical results while the article exists; the difference is that a
     * receipt for a deleted article stays findable instead of being
     * retained and unreachable.
     */
    Optional<ArticleReadReceipt> findByArticleIdSnapshotAndArticleVersionAndOperatorId(
            Long articleIdSnapshot, int articleVersion, Long operatorId);

    List<ArticleReadReceipt> findByArticleIdSnapshotAndArticleVersion(
            Long articleIdSnapshot, int articleVersion, Pageable pageable);

    /**
     * Port of _upsert_read_receipt's race-safety guarantee
     * (routers/articles.py:1132-1185) without Python's
     * attempt-then-catch-IntegrityError-then-retry: a literal insert-then-catch
     * translation would either poison the surrounding @Transactional method
     * on failure, or (with REQUIRES_NEW) risk deadlocking against a caller's
     * own uncommitted work on the same row.
     *
     * <p>This was one MERGE, documented as atomic. It is not: two MERGEs for
     * the same (article, version, operator) both evaluate ON against committed
     * data, both take NOT MATCHED, and the second waits on the first's
     * uncommitted key and then fails with ORA-00001 -- a 500 on a double
     * click (ConcurrentUpsertIntegrationTest). The insert now carries
     * {@code IGNORE_ROW_ON_DUPKEY_INDEX}, so the waiting one skips its row
     * instead of raising, and whichever call inserted nothing updates the
     * committed row -- the MATCHED branch, reached without an exception.
     * Same arguments and same effect for both callers.
     */
    default void upsert(
            Long articleId,
            String articleTitleSnapshot,
            int articleVersion,
            Long operatorId,
            String operatorNameSnapshot,
            String operatorEmailSnapshot,
            String operatorDepartmentSnapshot,
            OffsetDateTime readAt) {
        int inserted = insertIfMissing(articleId, articleTitleSnapshot, articleVersion, operatorId,
                operatorNameSnapshot, operatorEmailSnapshot, operatorDepartmentSnapshot, readAt);
        if (inserted == 0) {
            refreshExisting(articleId, articleTitleSnapshot, articleVersion, operatorId,
                    operatorNameSnapshot, operatorEmailSnapshot, operatorDepartmentSnapshot, readAt);
        }
    }

    /** First half of {@link #upsert}; clearAutomatically guards the read-back query that always follows it. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(article_read_receipts (article_id, article_version, operator_id)) */
            INTO article_read_receipts (
                article_id, article_id_snapshot, article_title_snapshot, article_version, operator_id,
                operator_name_snapshot, operator_email_snapshot, operator_department_snapshot, read_at
            )
            SELECT :articleId, :articleId, :articleTitleSnapshot, :articleVersion, :operatorId,
                   :operatorNameSnapshot, :operatorEmailSnapshot, :operatorDepartmentSnapshot, :readAt
            FROM dual
            WHERE NOT EXISTS (
                SELECT 1 FROM article_read_receipts
                WHERE article_id = :articleId AND article_version = :articleVersion AND operator_id = :operatorId
            )
            """, nativeQuery = true)
    int insertIfMissing(
            @Param("articleId") Long articleId,
            @Param("articleTitleSnapshot") String articleTitleSnapshot,
            @Param("articleVersion") int articleVersion,
            @Param("operatorId") Long operatorId,
            @Param("operatorNameSnapshot") String operatorNameSnapshot,
            @Param("operatorEmailSnapshot") String operatorEmailSnapshot,
            @Param("operatorDepartmentSnapshot") String operatorDepartmentSnapshot,
            @Param("readAt") OffsetDateTime readAt);

    /** Second half of {@link #upsert}: the former MERGE's WHEN MATCHED branch, unchanged. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE article_read_receipts SET
                read_at = :readAt,
                article_title_snapshot = :articleTitleSnapshot,
                operator_name_snapshot = :operatorNameSnapshot,
                operator_email_snapshot = :operatorEmailSnapshot,
                operator_department_snapshot = :operatorDepartmentSnapshot
            WHERE article_id = :articleId AND article_version = :articleVersion AND operator_id = :operatorId
            """, nativeQuery = true)
    int refreshExisting(
            @Param("articleId") Long articleId,
            @Param("articleTitleSnapshot") String articleTitleSnapshot,
            @Param("articleVersion") int articleVersion,
            @Param("operatorId") Long operatorId,
            @Param("operatorNameSnapshot") String operatorNameSnapshot,
            @Param("operatorEmailSnapshot") String operatorEmailSnapshot,
            @Param("operatorDepartmentSnapshot") String operatorDepartmentSnapshot,
            @Param("readAt") OffsetDateTime readAt);
}
