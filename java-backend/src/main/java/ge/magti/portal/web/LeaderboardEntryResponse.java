package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors schemas.py's LeaderboardEntryResponse. */
public record LeaderboardEntryResponse(
        @JsonProperty("user_id") Long userId,
        @JsonProperty("user_name") String userName,
        String department,
        int score,
        int rank
) {
}
