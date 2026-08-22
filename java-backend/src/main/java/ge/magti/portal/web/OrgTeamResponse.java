package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One AD-owned group and its active membership count. */
public record OrgTeamResponse(
        Long id,
        @JsonProperty("stable_key") String stableKey,
        String name,
        @JsonProperty("is_active") boolean active,
        @JsonProperty("member_count") long memberCount
) {
}
