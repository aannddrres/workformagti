package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.Team;

import java.time.OffsetDateTime;

public record TeamResponse(Long id, String name, @JsonProperty("created_at") OffsetDateTime createdAt) {
    public static TeamResponse from(Team team) {
        return new TeamResponse(team.getId(), team.getName(), team.getCreatedAt());
    }
}
