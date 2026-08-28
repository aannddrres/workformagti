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
     * (routers/articles.py:1132-1185), but via a single atomic MERGE
     * instead of Python's attempt-then-catch-IntegrityError-then-retry.
     * Same reasoning as {@code ArticleHistoryRepository.archiveIfMissing}:
     * a literal insert-then-catch translation would either poison the
     * surrounding @Transactional method on failure, or (with
     * REQUIRES_NEW) risk deadlocking against a caller's own uncommitted
     * work on the same row. clearAutomatically guards the read-back
     * query that always follows this call.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            MERGE INTO article_read_receipts t
            USING (SELECT :articleId AS article_id, :articleVersion AS article_version, :operatorId AS operator_id FROM dual) s
            ON (t.article_id = s.article_id AND t.article_version = s.article_version AND t.operator_id = s.operator_id)
            WHEN MATCHED THEN UPDATE SET
                t.read_at = :readAt,
                t.article_title_snapshot = :articleTitleSnapshot,
                t.operator_name_snapshot = :operatorNameSnapshot,
                t.operator_email_snapshot = :operatorEmailSnapshot,
                t.operator_department_snapshot = :operatorDepartmentSnapshot
            WHEN NOT MATCHED THEN INSERT (
                article_id, article_id_snapshot, article_title_snapshot, article_version, operator_id,
                operator_name_snapshot, operator_email_snapshot, operator_department_snapshot, read_at
            )
            VALUES (
                :articleId, :articleId, :articleTitleSnapshot, :articleVersion, :operatorId,
                :operatorNameSnapshot, :operatorEmailSnapshot, :operatorDepartmentSnapshot, :readAt
            )
            """, nativeQuery = true)
    void upsert(
            @Param("articleId") Long articleId,
            @Param("articleTitleSnapshot") String articleTitleSnapshot,
            @Param("articleVersion") int articleVersion,
            @Param("operatorId") Long operatorId,
            @Param("operatorNameSnapshot") String operatorNameSnapshot,
            @Param("operatorEmailSnapshot") String operatorEmailSnapshot,
            @Param("operatorDepartmentSnapshot") String operatorDepartmentSnapshot,
            @Param("readAt") OffsetDateTime readAt);
}
