package ge.magti.portal.repository;

import ge.magti.portal.domain.ArticleViewLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArticleViewLogRepository extends JpaRepository<ArticleViewLog, Long> {

    List<ArticleViewLog> findByArticleIdOrderByViewedAtDesc(Long articleId);

    List<ArticleViewLog> findByArticleIdAndArticleVersionOrderByViewedAtDesc(Long articleId, int articleVersion);

    /** Mirrors get_my_recently_viewed's LIMIT 30 raw-row cap (routers/articles.py:1379) before de-duplication. */
    List<ArticleViewLog> findTop30ByOperatorIdOrderByViewedAtDesc(Long operatorId);
}
