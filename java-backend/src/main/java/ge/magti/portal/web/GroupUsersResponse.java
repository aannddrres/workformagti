package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.stats.GroupMemberCompletion;

import java.util.List;

/** Mirrors schemas.GroupUsersResponse (schemas.py:774-778). */
public record GroupUsersResponse(
        String department,
        @JsonProperty("group_name") String groupName,
        List<GroupMemberCompletion> users,
        int total) {
}
