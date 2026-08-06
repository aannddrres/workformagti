package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors schemas.py's UserStatusUpdate (schemas.py:70-72). */
public record UserStatusUpdateRequest(@JsonProperty("is_active") boolean active) {
}
