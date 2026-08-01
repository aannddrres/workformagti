package ge.magti.portal.repository;

import ge.magti.portal.domain.NewsHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsHistoryRepository extends JpaRepository<NewsHistory, Long> {
}
