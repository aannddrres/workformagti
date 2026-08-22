package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One employee whose compliance or visible-user count would change. */
public record AccessDiffRowResponse(
        @JsonProperty("user_id") Long userId,
        @JsonProperty("user_name") String userName,
        String role,
        AccessDiffComplianceResponse compliance,
        AccessDiffScopeResponse scope
) {
}
