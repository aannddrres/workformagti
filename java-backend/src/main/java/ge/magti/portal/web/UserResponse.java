package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.compliance.ReadingProgress;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors schemas.py's UserResponse (schemas.py:38-49). read_count /
 * required_count / progress_percentage are not columns on {@link User} --
 * Python only ever populates them ad hoc in list_users (routers/users.py:
 * 288-294); every other call site serializes a plain User row and gets
 * null for all three (Pydantic's Optional[int] = None default on a
 * never-set dynamic attribute). {@link #from(User)} mirrors that: nulls by
 * default, {@link #from(User, ReadingProgress)} is the list_users-only path.
 */
public record UserResponse(
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
        @JsonProperty("permission_overrides") List<PermissionOverrideResponse> permissionOverrides,
        @JsonProperty("lock_version") long lockVersion
) {
    public static UserResponse from(User user) {
        return from(user, null, List.of());
    }

    public static UserResponse from(User user, ReadingProgress progress) {
        return from(user, progress, List.of());
    }

    public static UserResponse from(User user, List<UserPermissionOverride> overrides) {
        return from(user, null, overrides);
    }

    public static UserResponse from(
            User user, ReadingProgress progress, List<UserPermissionOverride> overrides) {
        return new UserResponse(
                user.getId(), user.getEmail(), user.getName(), user.getDepartment(), user.getPosition(),
                user.getPhone(), user.getRole().value(), user.getTeamId(), user.isActive(), user.getLastActive(),
                progress == null ? null : progress.readCount(),
                progress == null ? null : progress.requiredCount(),
                progress == null ? null : progress.percentage(),
                user.getCardStyle(), new ArrayList<>(user.getPermissions()),
                overrides.stream()
                        .sorted(java.util.Comparator.comparing(UserPermissionOverride::getPermission))
                        .map(PermissionOverrideResponse::from)
                        .toList(),
                user.getLockVersion());
    }
}
