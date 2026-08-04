package ge.magti.portal.repository;

import ge.magti.portal.domain.NewsHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NewsHistoryRepository extends JpaRepository<NewsHistory, Long> {

    List<NewsHistory> findByNewsIdOrderByUpdatedAtDesc(Long newsId);

    Optional<NewsHistory> findByIdAndNewsId(Long id, Long newsId);
}
