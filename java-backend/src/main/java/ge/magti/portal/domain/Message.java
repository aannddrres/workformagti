package ge.magti.portal.domain;

import java.time.OffsetDateTime;

/**
 * Mirrors models.py's Message (models.py:311-333, table {@code messages}) --
 * a direct manager-to-operator message, distinct from the SSE broadcast
 * events in {@link ge.magti.portal.messaging.SseEventVisibility}.
 *
 * <p>{@link #senderName}/{@link #recipientName} mirror the Python
 * {@code @property} accessors backed by the {@code sender}/{@code recipient}
 * relationships (models.py:327-333) -- plain fields here, not recomputed,
 * same rule already used for {@code Article.categoryName}.
 */
public class Message {

    private Long id;
    private Long userId;
    private Long senderId;
    private String content;
    private boolean read = false;
    private OffsetDateTime createdAt;
    private String senderName;
    private String recipientName;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public void setRecipientName(String recipientName) {
        this.recipientName = recipientName;
    }
}
