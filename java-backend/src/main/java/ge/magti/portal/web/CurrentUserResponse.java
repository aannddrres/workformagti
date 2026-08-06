package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.User;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors schemas.py's CurrentUserResponse (schemas.py:52-57) -- UserResponse
 * plus can_view_audit_log, flattened into one JSON object exactly like
 * Pydantic's subclass does. Kept as its own record (field list duplicated
 * from {@link UserResponse}) rather than composition, matching this port's
 * existing DTO convention (see e.g. News's summary/full response pair).
 */
public record CurrentUserResponse(
        Long id,
        String email,
        String name,
        String department,
        String position,
        String phone,
        @JsonProperty("role") String role,
        @JsonProperty("team_id") Long teamId,
        @JsonProperty("is_active") boolean active,
        @JsonProperty("last_active") OffsetDateTime lastActive,
        @JsonProperty("read_count") Integer readCount,
        @JsonProperty("required_count") Integer requiredCount,
        @JsonProperty("progress_percentage") Integer progressPercentage,
        @JsonProperty("card_style") String cardStyle,
        List<String> permissions,
        @JsonProperty("can_view_audit_log") boolean canViewAuditLog
) {
    public static CurrentUserResponse from(User user, boolean canViewAuditLog) {
        return new CurrentUserResponse(
                user.getId(), user.getEmail(), user.getName(), user.getDepartment(), user.getPosition(),
                user.getPhone(), user.getRole().value(), user.getTeamId(), user.isActive(), user.getLastActive(),
                null, null, null,
                user.getCardStyle(), new ArrayList<>(user.getPermissions()), canViewAuditLog);
    }
}
