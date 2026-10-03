package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** One entry of the notifications-summary recent-news list. */
public record RecentNewsSummaryItem(
        Long id,
        String title,
        @JsonProperty("target_department") String targetDepartment,
        @JsonProperty("created_at") OffsetDateTime createdAt
) {
}
