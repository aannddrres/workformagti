package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * {@link #itemType}/{@link #itemId} is a polymorphic reference (an
 * article or a news item today) rather than a foreign key to one table --
 * the referenced item's title is resolved by
 * {@link ge.magti.portal.content.ItemTitleResolver}.
 */
@Entity
@Table(name = "required_readings")
public class RequiredReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "item_type", nullable = false, length = 20)
    private String itemType;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "item_title_snapshot", length = 500)
    private String itemTitleSnapshot;

    @Column(name = "target_department", length = 200)
    private String targetDepartment = "All";

    @Column(name = "due_date", nullable = false)
    private OffsetDateTime dueDate;

    @Column(name = "priority", length = 20)
    private String priority = "normal";

    /**
     * When the ASSIGNMENT reminders went out; null until then (V52, PO-40).
     * Null only for a reading made mandatory before its article is published.
     */
    @Column(name = "assignment_delivered_at")
    private OffsetDateTime assignmentDeliveredAt;

    public OffsetDateTime getAssignmentDeliveredAt() {
        return assignmentDeliveredAt;
    }

    public void setAssignmentDeliveredAt(OffsetDateTime assignmentDeliveredAt) {
        this.assignmentDeliveredAt = assignmentDeliveredAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getItemType() {
        return itemType;
    }

    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public String getItemTitleSnapshot() {
        return itemTitleSnapshot;
    }

    public void setItemTitleSnapshot(String itemTitleSnapshot) {
        this.itemTitleSnapshot = itemTitleSnapshot;
    }

    public String getTargetDepartment() {
        return targetDepartment;
    }

    public void setTargetDepartment(String targetDepartment) {
        this.targetDepartment = targetDepartment;
    }

    public OffsetDateTime getDueDate() {
        return dueDate;
    }

    public void setDueDate(OffsetDateTime dueDate) {
        this.dueDate = dueDate;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }
}
