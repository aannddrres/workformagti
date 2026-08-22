package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.Department;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;

import java.time.OffsetDateTime;

/** A resolved current or historical leadership assignment. */
public record LeadershipAssignmentResponse(
        Long id,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("user_name") String userName,
        @JsonProperty("user_email") String userEmail,
        String scope,
        @JsonProperty("department_id") Long departmentId,
        @JsonProperty("department_name") String departmentName,
        @JsonProperty("team_id") Long teamId,
        @JsonProperty("team_name") String teamName,
        @JsonProperty("assignment_type") String assignmentType,
        @JsonProperty("is_active") boolean active,
        @JsonProperty("started_at") OffsetDateTime startedAt,
        @JsonProperty("ended_at") OffsetDateTime endedAt,
        @JsonProperty("created_by") Long createdBy,
        String source
) {
    public static LeadershipAssignmentResponse from(
            LeadershipAssignment assignment, User leader, Department department, Team team) {
        return new LeadershipAssignmentResponse(
                assignment.getId(), assignment.getUserId(), leader.getName(), leader.getEmail(),
                assignment.scope().name(), assignment.getDepartmentId(),
                department == null ? null : department.getName(), assignment.getTeamId(),
                team == null ? null : team.getName(), assignment.getAssignmentType().name(),
                assignment.isActive(), assignment.getStartedAt(), assignment.getEndedAt(),
                assignment.getCreatedBy(), assignment.getSource().name());
    }
}
