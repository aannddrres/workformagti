package ge.magti.portal.repository;

import ge.magti.portal.domain.ArticleViewLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface ArticleViewLogRepository extends JpaRepository<ArticleViewLog, Long> {

    /** The recently-viewed list's LIMIT 30 raw-row cap, before de-duplication. */
    List<ArticleViewLog> findTop30ByOperatorIdOrderByViewedAtDesc(Long operatorId);

    /**
     * The activity trend's article-view fold-in for the "all"/"USER"
     * category views -- article views live here,
     * not in audit_logs, but were the bulk of the USER-category signal, so
     * they're folded back into the same bucket keys as
     * {@link AuditLogRepository#countByDayBucket}. Object[] = {bucketKey (String), count (Number)}.
     */
    @Query(value = "SELECT TO_CHAR(TRUNC(viewed_at), 'YYYY-MM-DD') AS bucket_key, COUNT(*) AS cnt "
            + "FROM article_view_logs WHERE viewed_at >= :cutoff "
            + "GROUP BY TO_CHAR(TRUNC(viewed_at), 'YYYY-MM-DD')", nativeQuery = true)
    List<Object[]> countByDayBucket(@Param("cutoff") OffsetDateTime cutoff);

    /** The same fold-in for the hour buckets. */
    @Query(value = "SELECT TO_CHAR(viewed_at, 'YYYY-MM-DD HH24\":00\"') AS bucket_key, COUNT(*) AS cnt "
            + "FROM article_view_logs WHERE viewed_at >= :cutoff "
            + "GROUP BY TO_CHAR(viewed_at, 'YYYY-MM-DD HH24\":00\"')", nativeQuery = true)
    List<Object[]> countByHourBucket(@Param("cutoff") OffsetDateTime cutoff);
}
