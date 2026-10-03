package ge.magti.portal.repository;

import ge.magti.portal.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * Audit rows other than the given action -- for tests that assert a refused
     * request recorded no change, now that refusals are themselves recorded
     * as ACCESS_DENIED (simulation, 2026-10-01).
     */
    long countByActionNot(String action);


    /**
     * The activity trend's day buckets: one TO_CHAR(TRUNC(...))
     * format model produces the bucket key directly as a string ("YYYY-MM-DD"),
     * so no JDBC date-type mapping ambiguity
     * has to be handled on the Java side. {@code category} is optional (null =
     * no filter); the caller passes an already-uppercased value.
     * Object[] = {bucketKey (String), count (Number)}.
     */
    @Query(value = "SELECT TO_CHAR(TRUNC(timestamp), 'YYYY-MM-DD') AS bucket_key, COUNT(*) AS cnt "
            + "FROM audit_logs WHERE timestamp >= :cutoff AND (:category IS NULL OR category = :category) "
            + "GROUP BY TO_CHAR(TRUNC(timestamp), 'YYYY-MM-DD')", nativeQuery = true)
    List<Object[]> countByDayBucket(@Param("cutoff") OffsetDateTime cutoff, @Param("category") String category);

    /** The activity trend's hour buckets -- same idea, truncated to the hour. */
    @Query(value = "SELECT TO_CHAR(timestamp, 'YYYY-MM-DD HH24\":00\"') AS bucket_key, COUNT(*) AS cnt "
            + "FROM audit_logs WHERE timestamp >= :cutoff AND (:category IS NULL OR category = :category) "
            + "GROUP BY TO_CHAR(timestamp, 'YYYY-MM-DD HH24\":00\"')", nativeQuery = true)
    List<Object[]> countByHourBucket(@Param("cutoff") OffsetDateTime cutoff, @Param("category") String category);
}
