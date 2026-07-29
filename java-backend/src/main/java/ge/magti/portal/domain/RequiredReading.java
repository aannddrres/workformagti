package ge.magti.portal.domain;

import java.time.OffsetDateTime;

/**
 * Mirrors models.py's RequiredReading (models.py:201-214). Plain shape
 * only, no persistence annotations (Phase 1b), same rule as {@link User}.
 *
 * <p>{@link #itemType}/{@link #itemId} is a polymorphic reference (an
 * article or a news item today) rather than a foreign key to one table --
 * Python resolves the referenced item's title via
 * {@code db_helpers.resolve_item_title(s_bulk)}. That resolution helper is
 * not ported here; it's needed once a repository layer can actually look
 * items up.
 */
public class RequiredReading {

    private Long id;
    private String itemType;
    private Long itemId;
    private String targetDepartment = "All";
    private OffsetDateTime dueDate;
    private String priority = "normal";

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
