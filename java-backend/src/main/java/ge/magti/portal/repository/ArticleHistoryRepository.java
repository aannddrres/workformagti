package ge.magti.portal.repository;

import ge.magti.portal.article.ArticleHistorySummary;
import ge.magti.portal.domain.ArticleHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ArticleHistoryRepository extends JpaRepository<ArticleHistory, Long>, ArticleHistoryRepositoryCustom {

    List<ArticleHistory> findByArticleId(Long articleId);

    /** Mirrors get_article_history's order_by (routers/articles.py:551). */
    List<ArticleHistory> findByArticleIdOrderByUpdatedAtDesc(Long articleId, Pageable pageable);

    /**
     * CLOB-free list companion for interactive history screens. The legacy
     * full endpoint remains available, while first-party UI loads one content
     * snapshot only when the user expands it.
     */
    @Query("""
            SELECT new ge.magti.portal.article.ArticleHistorySummary(
                h.id, h.title, h.updatedAt, h.updatedBy, h.versionId)
            FROM ArticleHistory h
            WHERE h.articleId = :articleId
            ORDER BY h.updatedAt DESC
            """)
    List<ArticleHistorySummary> findSummaryByArticleIdOrderByUpdatedAtDesc(
            @Param("articleId") Long articleId, Pageable pageable);

    /** Aggregate CLOB characters without materializing any history content. */
    @Query(value = """
            SELECT NVL(SUM(DBMS_LOB.GETLENGTH(content)), 0)
            FROM article_history
            WHERE article_id = :articleId
            """, nativeQuery = true)
    long totalContentCharactersByArticleId(@Param("articleId") Long articleId);

    /** Mirrors get_article_versions' order_by (routers/articles.py:954). */
    List<ArticleHistory> findByArticleIdOrderByVersionIdDesc(Long articleId, Pageable pageable);

    /** Reader-facing version metadata never selects the content CLOB. */
    @Query("""
            SELECT new ge.magti.portal.article.ArticleHistorySummary(
                h.id, h.title, h.updatedAt, h.updatedBy, h.versionId)
            FROM ArticleHistory h
            WHERE h.articleId = :articleId
            ORDER BY h.versionId DESC
            """)
    List<ArticleHistorySummary> findSummaryByArticleIdOrderByVersionIdDesc(
            @Param("articleId") Long articleId, Pageable pageable);

    /** Scopes a {history_id} path param to its owning article -- a snapshot from a different article must not resolve. */
    Optional<ArticleHistory> findByIdAndArticleId(Long id, Long articleId);

    /** Mirrors get_article_diff's predecessor lookup (routers/articles.py:612-615). */
    Optional<ArticleHistory> findFirstByArticleIdAndVersionIdLessThanOrderByVersionIdDesc(Long articleId, Integer versionId);

    boolean existsByArticleIdAndVersionId(Long articleId, Integer versionId);

}
