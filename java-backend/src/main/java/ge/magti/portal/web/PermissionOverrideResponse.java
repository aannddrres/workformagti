package ge.magti.portal.web;

import ge.magti.portal.domain.UserPermissionOverride;

public record PermissionOverrideResponse(String permission, String state) {

    static PermissionOverrideResponse from(UserPermissionOverride override) {
        return new PermissionOverrideResponse(override.getPermission(), override.getState().name());
    }
}
