package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.PortalSession;

import java.time.OffsetDateTime;

public record PortalSessionResponse(
        String id,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("last_seen_at") OffsetDateTime lastSeenAt,
        @JsonProperty("expires_at") OffsetDateTime expiresAt,
        @JsonProperty("revoked_at") OffsetDateTime revokedAt,
        @JsonProperty("client_ip") String clientIp,
        @JsonProperty("user_agent") String userAgent,
        boolean current) {
    public static PortalSessionResponse from(PortalSession session, String currentSessionId) {
        return new PortalSessionResponse(session.getId(), session.getCreatedAt(), session.getLastSeenAt(),
                session.getExpiresAt(), session.getRevokedAt(), session.getClientIp(), session.getUserAgent(),
                session.getId().equals(currentSessionId));
    }
}
