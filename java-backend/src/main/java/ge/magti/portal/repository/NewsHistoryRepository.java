package ge.magti.portal.repository;

import ge.magti.portal.domain.NewsHistory;
import ge.magti.portal.news.NewsHistorySummary;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NewsHistoryRepository extends JpaRepository<NewsHistory, Long> {

    List<NewsHistory> findByNewsIdOrderByUpdatedAtDesc(Long newsId, Pageable pageable);

    /** CLOB-free history metadata for list-first/detail-on-demand clients. */
    @Query("""
            SELECT new ge.magti.portal.news.NewsHistorySummary(
                h.id, h.title, h.attachmentUrl, h.updatedAt, h.updatedBy)
            FROM NewsHistory h
            WHERE h.newsId = :newsId
            ORDER BY h.updatedAt DESC
            """)
    List<NewsHistorySummary> findSummaryByNewsIdOrderByUpdatedAtDesc(
            @Param("newsId") Long newsId, Pageable pageable);

    /** Aggregate CLOB characters without materializing any history content. */
    @Query(value = """
            SELECT NVL(SUM(DBMS_LOB.GETLENGTH(content)), 0)
            FROM news_history
            WHERE news_id = :newsId
            """, nativeQuery = true)
    long totalContentCharactersByNewsId(@Param("newsId") Long newsId);

    Optional<NewsHistory> findByIdAndNewsId(Long id, Long newsId);
}
