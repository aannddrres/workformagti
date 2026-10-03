package ge.magti.portal.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One member in the department dashboard.
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
