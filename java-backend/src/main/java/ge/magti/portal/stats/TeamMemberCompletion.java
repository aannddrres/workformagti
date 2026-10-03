package ge.magti.portal.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One member in the team stats. Note
 * {@link #percentageLabel} is a pre-formatted "NN%" string, not a plain
 * int -- that's what these two endpoints actually return today (unlike
 * every other stats endpoint in this codebase, which returns a plain
 * int percentage), carried over as-is rather than "fixed" to match. The
 * JSON key is still "percentage", even though the Java
 * field is named percentageLabel for clarity about its string type.
 */
public record TeamMemberCompletion(
        @JsonProperty("user_id") Long userId,
        @JsonProperty("user_name") String userName,
        @JsonProperty("read_count") int readCount,
        @JsonProperty("required_count") int requiredCount,
        @JsonProperty("percentage") String percentageLabel) {
}
