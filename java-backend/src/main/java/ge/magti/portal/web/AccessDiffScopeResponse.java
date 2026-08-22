package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Counts only: never the identities of employees inside either scope. */
public record AccessDiffScopeResponse(
        @JsonProperty("legacy_user_count") int legacyUserCount,
        @JsonProperty("proposed_user_count") int proposedUserCount
) {
}
