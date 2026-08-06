package ge.magti.portal.repository;

import ge.magti.portal.domain.SearchLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SearchLogRepository extends JpaRepository<SearchLog, Long> {

    /** Mirrors get_search_history (routers/search.py:258-285): most recent first, caller passes limit-50 via Pageable. */
    List<SearchLog> findByUserIdOrderByTimestampDesc(Long userId, Pageable pageable);

    /**
     * Mirrors get_popular_searches (routers/stats.py:94-116): normalised
     * (lower+trim) search terms among logs that did return results, most
     * frequent first. Object[] = {searchTerm (String), count (Long)}.
     */
    @Query("SELECT LOWER(TRIM(s.searchTerm)), COUNT(s.id) FROM SearchLog s "
            + "WHERE s.hasResults = true GROUP BY LOWER(TRIM(s.searchTerm)) ORDER BY COUNT(s.id) DESC")
    List<Object[]> popularSearchTerms(Pageable pageable);

    /** Mirrors get_failed_searches (routers/stats.py:119-134) -- same shape, hasResults = false. */
    @Query("SELECT LOWER(TRIM(s.searchTerm)), COUNT(s.id) FROM SearchLog s "
            + "WHERE s.hasResults = false GROUP BY LOWER(TRIM(s.searchTerm)) ORDER BY COUNT(s.id) DESC")
    List<Object[]> failedSearchTerms(Pageable pageable);
}
