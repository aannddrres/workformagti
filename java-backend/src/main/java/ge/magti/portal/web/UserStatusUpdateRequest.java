package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UserStatusUpdateRequest(@JsonProperty("is_active") boolean active) {
}
