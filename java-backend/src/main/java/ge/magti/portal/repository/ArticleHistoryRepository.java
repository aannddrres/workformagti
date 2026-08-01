package ge.magti.portal.repository;

import ge.magti.portal.domain.ArticleHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ArticleHistoryRepository extends JpaRepository<ArticleHistory, Long> {

    List<ArticleHistory> findByArticleId(Long articleId);

    /** Mirrors get_article_history's order_by (routers/articles.py:551). */
    List<ArticleHistory> findByArticleIdOrderByUpdatedAtDesc(Long articleId);

    /** Mirrors get_article_versions' order_by (routers/articles.py:954). */
    List<ArticleHistory> findByArticleIdOrderByVersionIdDesc(Long articleId);

    /** Scopes a {history_id} path param to its owning article -- a snapshot from a different article must not resolve. */
    Optional<ArticleHistory> findByIdAndArticleId(Long id, Long articleId);

    /** Mirrors get_article_diff's predecessor lookup (routers/articles.py:612-615). */
    Optional<ArticleHistory> findFirstByArticleIdAndVersionIdLessThanOrderByVersionIdDesc(Long articleId, Integer versionId);

    boolean existsByArticleIdAndVersionId(Long articleId, Integer versionId);

    /**
     * Port of _ensure_current_version_archived's race-safety guarantee
     * (routers/articles.py:265-297), but via a single atomic statement
     * instead of Python's attempt-then-catch-IntegrityError approach.
     *
     * <p>A literal translation (insert, catch the unique-constraint
     * violation, roll back and continue) doesn't survive the move to
     * Spring: once a flush fails inside a shared {@code @Transactional}
     * method, Hibernate marks that whole transaction rollback-only, so
     * catching the exception wouldn't let the rest of update_article's
     * work still commit. A {@code REQUIRES_NEW} nested transaction was
     * tried first and rejected -- it isolates the failure correctly, but
     * a caller that has any uncommitted work on the same {@code articles}
     * row (in production, never happens before this call; in a
     * {@code @Transactional} test that sets up its fixture in the same
     * transaction, always happens) blocks on the FK's row-level lock,
     * self-deadlocking against its own suspended outer transaction. This
     * WHERE-NOT-EXISTS guard needs no second connection and never throws
     * for the common case, sidestepping both problems.
     */
    @Modifying
    @Query(value = """
            INSERT INTO article_history (article_id, title, content, updated_by, version_id, updated_at)
            SELECT :articleId, :title, :content, :updatedBy, :versionId, :updatedAt FROM dual
            WHERE NOT EXISTS (
                SELECT 1 FROM article_history WHERE article_id = :articleId AND version_id = :versionId
            )
            """, nativeQuery = true)
    void archiveIfMissing(
            @Param("articleId") Long articleId, @Param("title") String title, @Param("content") String content,
            @Param("updatedBy") Long updatedBy, @Param("versionId") Integer versionId,
            @Param("updatedAt") OffsetDateTime updatedAt);
}
