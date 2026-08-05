package ge.magti.portal.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mirrors the per-user dict routers/stats.py's get_group_users builds
 * (routers/stats.py:802-806), matching schemas.GroupUserStat
 * (schemas.py:767-771) field-for-field.
 */
public record GroupMemberCompletion(
        @JsonProperty("user_id") Long userId,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        @JsonProperty("completion_percentage") int completionPercentage) {
}
