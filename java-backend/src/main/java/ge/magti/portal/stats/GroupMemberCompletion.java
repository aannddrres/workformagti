package ge.magti.portal.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One user in the group-users list.
 */
public record GroupMemberCompletion(
        @JsonProperty("user_id") Long userId,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        @JsonProperty("completion_percentage") int completionPercentage) {
}
