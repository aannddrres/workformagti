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

/** A durable company-wide announcement; it has no recipient or read state. */
@Entity
@Table(name = "broadcasts")
public class BroadcastAnnouncement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Lob
    @Column(nullable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BroadcastPriority priority;

    @Column(name = "published_at", nullable = false)
    private OffsetDateTime publishedAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "published_by_user_id")
    private Long publishedByUserId;

    @Column(name = "publisher_name_snapshot", nullable = false, length = 255)
    private String publisherNameSnapshot;

    @Column(name = "ended_by_user_id")
    private Long endedByUserId;

    @Column(name = "ended_by_name_snapshot", length = 255)
    private String endedByNameSnapshot;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public BroadcastPriority getPriority() { return priority; }
    public void setPriority(BroadcastPriority priority) { this.priority = priority; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(OffsetDateTime publishedAt) { this.publishedAt = publishedAt; }
    public OffsetDateTime getEndsAt() { return endsAt; }
    public void setEndsAt(OffsetDateTime endsAt) { this.endsAt = endsAt; }
    public OffsetDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(OffsetDateTime endedAt) { this.endedAt = endedAt; }
    public Long getPublishedByUserId() { return publishedByUserId; }
    public void setPublishedByUserId(Long publishedByUserId) { this.publishedByUserId = publishedByUserId; }
    public String getPublisherNameSnapshot() { return publisherNameSnapshot; }
    public void setPublisherNameSnapshot(String publisherNameSnapshot) { this.publisherNameSnapshot = publisherNameSnapshot; }
    public Long getEndedByUserId() { return endedByUserId; }
    public void setEndedByUserId(Long endedByUserId) { this.endedByUserId = endedByUserId; }
    public String getEndedByNameSnapshot() { return endedByNameSnapshot; }
    public void setEndedByNameSnapshot(String endedByNameSnapshot) { this.endedByNameSnapshot = endedByNameSnapshot; }
    public long getLockVersion() { return lockVersion; }
}
