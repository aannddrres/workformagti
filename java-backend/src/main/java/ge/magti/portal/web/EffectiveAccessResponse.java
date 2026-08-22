package ge.magti.portal.web;

import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Set;

/** The caller's effective authorization decision, without profile or scope data. */
public record EffectiveAccessResponse(
        String role,
        List<String> permissions,
        boolean bypass,
        @JsonProperty("can_publish_announcement") boolean canPublishAnnouncement
) {
    public static EffectiveAccessResponse from(
            User user, Set<Permission> effectivePermissions, boolean canPublishAnnouncement) {
        List<String> permissions = effectivePermissions.stream()
                .map(Permission::value)
                .sorted()
                .toList();
        return new EffectiveAccessResponse(
                user.getRole().value(), permissions, user.getRole() == Role.SYSTEM_ADMIN, canPublishAnnouncement);
    }
}
