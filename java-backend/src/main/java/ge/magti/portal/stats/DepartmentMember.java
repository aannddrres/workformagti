package ge.magti.portal.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mirrors the per-member dict routers/stats.py's build_department_stats
 * builds (routers/stats.py:607-615), matching schemas.DeptMemberStats
 * (schemas.py:692-700) field-for-field.
 */
public record DepartmentMember(
        @JsonProperty("user_id") Long userId,
        @JsonProperty("user_name") String userName,
        String position,
        @JsonProperty("read_count") int readCount,
        @JsonProperty("required_count") int requiredCount,
        int percentage,
        @JsonProperty("is_critical") boolean critical) {
}
