package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/** Mirrors schemas.py's LeaderboardResponse. */
public record LeaderboardResponse(
        List<LeaderboardEntryResponse> entries,
        @JsonProperty("generated_at") OffsetDateTime generatedAt
) {
}
