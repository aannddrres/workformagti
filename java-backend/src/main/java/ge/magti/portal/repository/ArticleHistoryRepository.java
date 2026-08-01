package ge.magti.portal.repository;

import ge.magti.portal.domain.ArticleHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArticleHistoryRepository extends JpaRepository<ArticleHistory, Long> {
}
