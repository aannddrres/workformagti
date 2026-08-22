package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.BroadcastAnnouncement;
import ge.magti.portal.domain.BroadcastPriority;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;

import java.time.OffsetDateTime;

public record BroadcastResponse(
        Long id,
        String message,
        BroadcastPriority priority,
        @JsonProperty("published_at") OffsetDateTime publishedAt,
        @JsonProperty("ends_at") OffsetDateTime endsAt,
        @JsonProperty("ended_at") OffsetDateTime endedAt,
        @JsonProperty("publisher_name") String publisherName,
        @JsonProperty("ended_by_name") String endedByName,
        String status,
        @JsonProperty("can_end_early") boolean canEndEarly,
        @JsonProperty("lock_version") long lockVersion
) {
    public static BroadcastResponse from(BroadcastAnnouncement value, OffsetDateTime now, User viewer) {
        String status = value.getEndedAt() != null ? "ended"
                : value.getEndsAt().isAfter(now) ? "active" : "expired";
        boolean canEndEarly = "active".equals(status) && viewer != null
                && (viewer.getRole() == Role.SYSTEM_ADMIN
                || viewer.getId().equals(value.getPublishedByUserId()));
        return new BroadcastResponse(
                value.getId(), value.getMessage(), value.getPriority(), value.getPublishedAt(), value.getEndsAt(),
                value.getEndedAt(), value.getPublisherNameSnapshot(), value.getEndedByNameSnapshot(), status, canEndEarly,
                value.getLockVersion());
    }
}
