package ge.magti.portal.repository;

import ge.magti.portal.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * Mirrors get_activity_trend's day-bucket branch (routers/stats.py:818-894),
     * Oracle-native replacement for Python's Postgres/SQLite date_trunc/strftime
     * dialect branching -- now that this is Oracle-only, one TO_CHAR(TRUNC(...))
     * format model produces the bucket key directly as a string ("YYYY-MM-DD"),
     * matching Python's key_fmt exactly, so no JDBC date-type mapping ambiguity
     * has to be handled on the Java side. {@code category} is optional (null =
     * no filter, matching Python's {@code if category:}); the caller passes an
     * already-uppercased value, matching Python's {@code category.upper()}.
     * Object[] = {bucketKey (String), count (Number)}.
     */
    @Query(value = "SELECT TO_CHAR(TRUNC(timestamp), 'YYYY-MM-DD') AS bucket_key, COUNT(*) AS cnt "
            + "FROM audit_logs WHERE timestamp >= :cutoff AND (:category IS NULL OR category = :category) "
            + "GROUP BY TO_CHAR(TRUNC(timestamp), 'YYYY-MM-DD')", nativeQuery = true)
    List<Object[]> countByDayBucket(@Param("cutoff") OffsetDateTime cutoff, @Param("category") String category);

    /** Mirrors get_activity_trend's hour-bucket branch -- same idea, truncated to the hour. */
    @Query(value = "SELECT TO_CHAR(timestamp, 'YYYY-MM-DD HH24\":00\"') AS bucket_key, COUNT(*) AS cnt "
            + "FROM audit_logs WHERE timestamp >= :cutoff AND (:category IS NULL OR category = :category) "
            + "GROUP BY TO_CHAR(timestamp, 'YYYY-MM-DD HH24\":00\"')", nativeQuery = true)
    List<Object[]> countByHourBucket(@Param("cutoff") OffsetDateTime cutoff, @Param("category") String category);
}
