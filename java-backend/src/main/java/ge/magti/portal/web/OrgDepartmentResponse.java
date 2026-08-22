package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** One AD-owned department in the read-only organisation tree. */
public record OrgDepartmentResponse(
        Long id,
        @JsonProperty("stable_key") String stableKey,
        String name,
        @JsonProperty("is_active") boolean active,
        List<OrgTeamResponse> teams
) {
}
