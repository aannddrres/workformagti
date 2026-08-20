package ge.magti.portal.repository;

import ge.magti.portal.domain.ArticleHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArticleHistoryRepository extends JpaRepository<ArticleHistory, Long>, ArticleHistoryRepositoryCustom {

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

}
