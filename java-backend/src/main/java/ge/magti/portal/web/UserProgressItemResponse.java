package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Percentage is a pre-formatted "NN%" string.
 */
public record UserProgressItemResponse(
        @JsonProperty("user_id") Long userId,
        @JsonProperty("user_name") String userName,
        String department,
        @JsonProperty("read_count") int readCount,
        @JsonProperty("required_count") int requiredCount,
        String percentage) {
}
