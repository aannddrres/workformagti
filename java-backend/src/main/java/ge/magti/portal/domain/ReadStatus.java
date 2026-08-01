package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;

/**
 * Mirrors models.py's ReadStatus (models.py:217-237).
 *
 * <p>{@link #operatorDepartmentSnapshot} is a denormalized copy of the
 * user's department at the moment this row was marked "read" -- same
 * reasoning as {@link ArticleReadReceipt}'s snapshot fields: a later
 * department/group move must not retroactively rewrite historical
 * compliance numbers.
 */
@Entity
@Table(name = "read_statuses", uniqueConstraints = @UniqueConstraint(
        name = "uq_read_status_user_reading", columnNames = {"user_id", "required_reading_id"}))
public class ReadStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "required_reading_id", nullable = false)
    private Long requiredReadingId;

    @Column(name = "status", length = 20)
    private String status = "unread";

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(name = "operator_department_snapshot", length = 200)
    private String operatorDepartmentSnapshot;

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

    public Long getRequiredReadingId() {
        return requiredReadingId;
    }

    public void setRequiredReadingId(Long requiredReadingId) {
        this.requiredReadingId = requiredReadingId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(OffsetDateTime readAt) {
        this.readAt = readAt;
    }

    public String getOperatorDepartmentSnapshot() {
        return operatorDepartmentSnapshot;
    }

    public void setOperatorDepartmentSnapshot(String operatorDepartmentSnapshot) {
        this.operatorDepartmentSnapshot = operatorDepartmentSnapshot;
    }
}
