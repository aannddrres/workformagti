package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.ReadStatus;

import java.time.OffsetDateTime;

/** Mirrors schemas.py's ReadStatusResponse -- what POST mark-read returns. */
public record ReadStatusResponse(
        Long id,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("required_reading_id") Long requiredReadingId,
        String status,
        @JsonProperty("read_at") OffsetDateTime readAt
) {
    public static ReadStatusResponse from(ReadStatus stat) {
        return new ReadStatusResponse(stat.getId(), stat.getUserId(), stat.getRequiredReadingId(),
                stat.getStatus(), stat.getReadAt());
    }
}
