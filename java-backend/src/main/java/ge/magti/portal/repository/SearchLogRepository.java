package ge.magti.portal.repository;

import ge.magti.portal.domain.SearchLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SearchLogRepository extends JpaRepository<SearchLog, Long> {

    /** Search history: most recent first, caller passes limit-50 via Pageable. */
    // Id breaks a tie: two searches in the same instant came back in either
    // order, and "most recent first" was a coin toss (Oracle suite, 2026-10-02).
    List<SearchLog> findByUserIdOrderByTimestampDescIdDesc(Long userId, Pageable pageable);

    /**
     * Popular searches: normalised
     * (lower+trim) search terms among logs that did return results, most
     * frequent first. Object[] = {searchTerm (String), count (Long)}.
     */
    @Query("SELECT LOWER(TRIM(s.searchTerm)), COUNT(s.id) FROM SearchLog s "
            + "WHERE s.hasResults = true GROUP BY LOWER(TRIM(s.searchTerm)) ORDER BY COUNT(s.id) DESC")
    List<Object[]> popularSearchTerms(Pageable pageable);

    /** Failed searches -- same shape, hasResults = false. */
    @Query("SELECT LOWER(TRIM(s.searchTerm)), COUNT(s.id) FROM SearchLog s "
            + "WHERE s.hasResults = false GROUP BY LOWER(TRIM(s.searchTerm)) ORDER BY COUNT(s.id) DESC")
    List<Object[]> failedSearchTerms(Pageable pageable);
}
