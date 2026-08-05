package ge.magti.portal.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mirrors the per-operator dict routers/stats.py's get_critical_operators
 * builds (routers/stats.py:731-737), matching schemas.CriticalOperatorItem
 * (schemas.py:781-786) field-for-field.
 */
public record CriticalOperator(
        @JsonProperty("user_id") Long userId,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        String department,
        @JsonProperty("overdue_count") int overdueCount) {
}
