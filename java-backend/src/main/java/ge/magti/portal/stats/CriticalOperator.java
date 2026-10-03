package ge.magti.portal.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One operator in the critical-operators list.
 */
public record CriticalOperator(
        @JsonProperty("user_id") Long userId,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        String department,
        @JsonProperty("overdue_count") int overdueCount) {
}
