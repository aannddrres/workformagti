package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.User;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * UserResponse plus can_view_audit_log, flattened into one JSON object.
 * Kept as its own record (field list duplicated
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
        @JsonProperty("can_view_audit_log") boolean canViewAuditLog,
        // True when roles come from the company directory at sign-in: the
        // admin screens show the role read-only instead of offering an edit
        // the server will refuse.
        @JsonProperty("roles_managed_by_directory") boolean rolesManagedByDirectory
) {
    /**
     * @param effectivePermissions what the caller may actually do, as
     *     {@code PermissionChecker.effectivePermissions} resolves it. Passed
     *     in rather than read off {@code user.getPermissions()}: since the
     *     Phase 6 cutover that column no longer decides anything, so it can
     *     disagree with the gates in both directions -- listing a permission
     *     an explicit DENY has taken away, and omitting one an explicit ALLOW
     *     granted.
     */
    public static CurrentUserResponse from(
            User user, boolean canViewAuditLog, Set<Permission> effectivePermissions,
            boolean rolesManagedByDirectory) {
        List<String> permissions = new ArrayList<>();
        for (Permission permission : effectivePermissions) {
            permissions.add(permission.value());
        }
        return new CurrentUserResponse(
                user.getId(), user.getEmail(), user.getName(), user.getDepartment(), user.getPosition(),
                user.getPhone(), user.getRole().value(), user.getTeamId(), user.isActive(), user.getLastActive(),
                null, null, null,
                user.getCardStyle(), permissions, canViewAuditLog, rolesManagedByDirectory);
    }
}
