package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mirrors get_user_progress' ad-hoc dict (routers/stats.py:381-388) -- no
 * formal Pydantic schema in Python. Percentage is a pre-formatted "NN%"
 * string, matching Python's f-string.
 */
public record UserProgressItemResponse(
        @JsonProperty("user_id") Long userId,
        @JsonProperty("user_name") String userName,
        String department,
        @JsonProperty("read_count") int readCount,
        @JsonProperty("required_count") int requiredCount,
        String percentage) {
}
