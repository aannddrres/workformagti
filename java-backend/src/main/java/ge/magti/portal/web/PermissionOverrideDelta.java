package ge.magti.portal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PermissionOverrideDelta(
        @NotBlank String permission,
        @NotNull State state) {

    public enum State {
        INHERIT,
        ALLOW,
        DENY
    }
}
