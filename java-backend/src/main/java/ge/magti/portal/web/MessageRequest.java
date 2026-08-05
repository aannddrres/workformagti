package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/** Mirrors schemas.MessageCreate (schemas.py:484-487). */
public record MessageRequest(@JsonProperty("user_id") @NotNull Long userId, String content) {
}
