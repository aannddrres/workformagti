package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;

/** A fixed, one-way portal reminder; it is never a chat message. */
@Entity
@Table(name = "reminders")
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Column(name = "recipient_name_snapshot", nullable = false, length = 255)
    private String recipientNameSnapshot;

    @Column(name = "required_reading_id")
    private Long requiredReadingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reminder_type", nullable = false, length = 20)
    private ReminderType type;

    @Lob
    @Column(name = "content_snapshot", nullable = false)
    private String contentSnapshot;

    @Column(name = "item_type_snapshot", length = 20)
    private String itemTypeSnapshot;

    @Column(name = "item_id_snapshot")
    private Long itemIdSnapshot;

    @Column(name = "item_title_snapshot", length = 500)
    private String itemTitleSnapshot;

    @Column(name = "due_at_snapshot")
    private OffsetDateTime dueAtSnapshot;

    @Column(name = "triggered_by_user_id")
    private Long triggeredByUserId;

    @Column(name = "triggered_by_name_snapshot", nullable = false, length = 255)
    private String triggeredByNameSnapshot;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    public Long getId() { return id; }
    public Long getRecipientUserId() { return recipientUserId; }
    public void setRecipientUserId(Long value) { recipientUserId = value; }
    public String getRecipientNameSnapshot() { return recipientNameSnapshot; }
    public void setRecipientNameSnapshot(String value) { recipientNameSnapshot = value; }
    public Long getRequiredReadingId() { return requiredReadingId; }
    public void setRequiredReadingId(Long value) { requiredReadingId = value; }
    public ReminderType getType() { return type; }
    public void setType(ReminderType value) { type = value; }
    public String getContentSnapshot() { return contentSnapshot; }
    public void setContentSnapshot(String value) { contentSnapshot = value; }
    public String getItemTypeSnapshot() { return itemTypeSnapshot; }
    public void setItemTypeSnapshot(String value) { itemTypeSnapshot = value; }
    public Long getItemIdSnapshot() { return itemIdSnapshot; }
    public void setItemIdSnapshot(Long value) { itemIdSnapshot = value; }
    public String getItemTitleSnapshot() { return itemTitleSnapshot; }
    public void setItemTitleSnapshot(String value) { itemTitleSnapshot = value; }
    public OffsetDateTime getDueAtSnapshot() { return dueAtSnapshot; }
    public void setDueAtSnapshot(OffsetDateTime value) { dueAtSnapshot = value; }
    public Long getTriggeredByUserId() { return triggeredByUserId; }
    public void setTriggeredByUserId(Long value) { triggeredByUserId = value; }
    public String getTriggeredByNameSnapshot() { return triggeredByNameSnapshot; }
    public void setTriggeredByNameSnapshot(String value) { triggeredByNameSnapshot = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public OffsetDateTime getReadAt() { return readAt; }
    public void setReadAt(OffsetDateTime value) { readAt = value; }
    public long getLockVersion() { return lockVersion; }
}
