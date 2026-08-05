package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors schemas.BroadcastRequest (schemas.py:741-745). */
public record BroadcastRequest(
        String message,
        @JsonProperty("target_department") String targetDepartment,
        @JsonProperty("target_role") String targetRole
) {
    public String targetDepartmentOrDefault() {
        return (targetDepartment == null || targetDepartment.isBlank()) ? "All" : targetDepartment;
    }

    public String targetRoleOrDefault() {
        return (targetRole == null || targetRole.isBlank()) ? "All" : targetRole;
    }
}
