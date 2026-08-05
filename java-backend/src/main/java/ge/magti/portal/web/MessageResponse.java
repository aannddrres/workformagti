package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.Message;

import java.time.OffsetDateTime;

/**
 * Mirrors schemas.MessageResponse (schemas.py:490-500). {@code senderName}/
 * {@code recipientName} come from models.py's {@code sender_name}/
 * {@code recipient_name} @property accessors (models.py:327-333) -- a plain
 * User.name lookup, null if the sender/recipient no longer resolves --
 * resolved by the caller (batch, not per-row) rather than here.
 */
public record MessageResponse(
        Long id,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("sender_id") Long senderId,
        String content,
        @JsonProperty("is_read") boolean read,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("sender_name") String senderName,
        @JsonProperty("recipient_name") String recipientName
) {
    public static MessageResponse from(Message message, String senderName, String recipientName) {
        return new MessageResponse(
                message.getId(), message.getUserId(), message.getSenderId(), message.getContent(),
                message.isRead(), message.getCreatedAt(), senderName, recipientName);
    }
}
