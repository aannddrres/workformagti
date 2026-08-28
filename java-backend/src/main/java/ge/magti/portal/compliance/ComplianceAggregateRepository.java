package ge.magti.portal.compliance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.StringReader;
import java.util.List;

/**
 * Oracle query seam for the compliance numerator/denominator.
 *
 * <p>The old query grouped every historical read status for every selected
 * user by every target department and let Java discard the groups that were
 * irrelevant to that user's current department. That result could grow as
 * users x all historical targets. This seam accepts the exact, already
 * de-duplicated user/target pairs selected by {@link ComplianceProgressQueryService}
 * and returns exactly one aggregate row per pair (at most three per user).
 * Department parsing remains Java-owned by {@code DepartmentMatcher}; Oracle
 * only joins exact strings, so the established matching rule cannot drift into
 * a second SQL implementation.
 */
@Repository
public class ComplianceAggregateRepository {

    private static final String RELEVANT_COUNTS_SQL = """
            WITH scope_targets AS (
                SELECT jt.user_id, jt.target_department
                FROM JSON_TABLE(?, '$[*]'
                    COLUMNS (
                        user_id NUMBER PATH '$.userId',
                        target_department VARCHAR2(200 CHAR) PATH '$.targetDepartment'
                    )) jt
            )
            SELECT st.user_id,
                   st.target_department,
                   COUNT(rr.id) AS required_count,
                   COUNT(rs.id) AS read_count
            FROM scope_targets st
            LEFT JOIN required_readings rr
                   ON rr.target_department = st.target_department
            LEFT JOIN read_statuses rs
                   ON rs.required_reading_id = rr.id
                  AND rs.user_id = st.user_id
                  AND rs.status = 'read'
            GROUP BY st.user_id, st.target_department
            ORDER BY st.user_id, st.target_department
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ComplianceAggregateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AggregateRow> findRelevantCounts(List<ScopeTarget> scopeTargets) {
        if (scopeTargets.isEmpty()) {
            return List.of();
        }
        String json = serialize(scopeTargets);
        return jdbcTemplate.query(
                RELEVANT_COUNTS_SQL,
                statement -> statement.setClob(1, new StringReader(json)),
                (resultSet, rowNumber) -> new AggregateRow(
                        resultSet.getLong("user_id"),
                        resultSet.getString("target_department"),
                        Math.toIntExact(resultSet.getLong("required_count")),
                        Math.toIntExact(resultSet.getLong("read_count"))));
    }

    private String serialize(List<ScopeTarget> scopeTargets) {
        try {
            return objectMapper.writeValueAsString(scopeTargets);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize bounded compliance query scope", exception);
        }
    }

    public record ScopeTarget(long userId, String targetDepartment) {
    }

    public record AggregateRow(long userId, String targetDepartment, int requiredCount, int readCount) {
    }
}
