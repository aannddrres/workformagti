package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.stats.GroupMemberCompletion;

import java.util.List;

public record GroupUsersResponse(
        String department,
        @JsonProperty("group_name") String groupName,
        List<GroupMemberCompletion> users,
        int total) {
}
