package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.compliance.ReadingProgress;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.security.CapabilityService;

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
    // The two overloads that used to default `overrides` to List.of() are
    // gone on purpose. `permission_overrides: []` is a claim -- "this person
    // has no explicit ALLOW or DENY" -- and a caller that simply had not
    // loaded them made that claim by accident. Every caller now says which
    // one it means, and a caller that genuinely knows the list is empty
    // passes an empty list and says so.

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
                user.getCardStyle(), effectiveValues(user, overrides),
                overrides.stream()
                        .sorted(java.util.Comparator.comparing(UserPermissionOverride::getPermission))
                        .map(PermissionOverrideResponse::from)
                        .toList(),
                user.getLockVersion());
    }

    /**
     * What this user may actually do, not what {@code users.permissions}
     * happens to still say.
     *
     * <p>The two disagree from the Phase 6 cutover onwards: a role change no
     * longer rewrites the legacy column, and explicit ALLOW/DENY never
     * appeared in it at all. Resolving from the overrides that this response
     * already carries keeps the list honest without a query per row.
     */
    private static List<String> effectiveValues(User user, List<UserPermissionOverride> overrides) {
        List<String> values = new ArrayList<>();
        for (Permission permission : CapabilityService.effectivePermissions(user, overrides)) {
            values.add(permission.value());
        }
        return values;
    }
}
