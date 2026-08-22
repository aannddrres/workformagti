package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.AssignmentType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Exactly one of departmentId and teamId must be present. */
public record LeadershipAssignmentRequest(
        @JsonProperty("user_id") @NotNull @Positive Long userId,
        @JsonProperty("department_id") @Positive Long departmentId,
        @JsonProperty("team_id") @Positive Long teamId,
        @JsonProperty("assignment_type") @NotNull AssignmentType assignmentType
) {
}
