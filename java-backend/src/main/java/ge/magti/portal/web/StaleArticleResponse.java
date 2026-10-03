package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

public record StaleArticleResponse(
        Long id,
        String title,
        @JsonProperty("target_departments") List<String> targetDepartments,
        @JsonProperty("last_verified_at") OffsetDateTime lastVerifiedAt,
        @JsonProperty("days_stale") long daysStale
) {
}
