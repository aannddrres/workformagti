package ge.magti.portal.domain;

import java.time.OffsetDateTime;

/**
 * Mirrors models.py's ReadStatus (models.py:217-237). Plain shape only, no
 * persistence annotations (Phase 1b), same rule as {@link User}.
 *
 * <p>{@link #operatorDepartmentSnapshot} is a denormalized copy of the
 * user's department at the moment this row was marked "read" -- same
 * reasoning as {@link ArticleReadReceipt}'s snapshot fields: a later
 * department/group move must not retroactively rewrite historical
 * compliance numbers.
 */
public class ReadStatus {

    private Long id;
    private Long userId;
    private Long requiredReadingId;
    private String status = "unread";
    private OffsetDateTime readAt;
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
