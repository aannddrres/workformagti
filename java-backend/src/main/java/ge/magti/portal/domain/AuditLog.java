package ge.magti.portal.domain;

import ge.magti.portal.audit.AuditCategoryClassifier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * The audit_logs table.
 *
 * <p>{@link #prevHash}/{@link #rowHash}/{@link #ipAddress}/{@link #userAgent}
 * are populated by a database {@code BEFORE INSERT} trigger (V28) -- never
 * set from application code, and not settable here either; a
 * plain domain object has nowhere to run that trigger. NULL on this class
 * means "not yet chained by a trigger", not "tampered".
 *
 * <p><b>This is deliberately as far as this step goes.</b> The hash-chain
 * mechanism itself -- the actual security-critical, Oracle-specific
 * redesign -- is not attempted here; see the migration doc's §1a for the
 * freshly re-verified analysis of what it would take, and why none of it
 * can be honestly written without a real Oracle connection to test
 * against.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "admin_id")
    private Long adminId;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "item_type", nullable = false, length = 30)
    private String itemType;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "timestamp")
    private OffsetDateTime timestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20)
    private AuditCategory category;

    @Lob
    @Column(name = "details")
    private String details;

    @Column(name = "admin_name_snapshot", length = 255)
    private String adminNameSnapshot;

    @Column(name = "admin_email_snapshot", length = 255)
    private String adminEmailSnapshot;

    @Column(name = "item_name_snapshot", length = 500)
    private String itemNameSnapshot;

    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @Column(name = "row_hash", length = 64)
    private String rowHash;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /**
     * Classifies the row on every insert automatically, so no call site
     * needs to remember to set it. The snapshot columns
     * (admin_name_snapshot etc.) need a live DB lookup a {@code @PrePersist}
     * callback can't do -- that part is each call site's own job for now.
     */
    @PrePersist
    private void classifyCategoryIfMissing() {
        if (category == null) {
            category = AuditCategoryClassifier.classify(itemType, action);
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAdminId() {
        return adminId;
    }

    public void setAdminId(Long adminId) {
        this.adminId = adminId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
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

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(OffsetDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public AuditCategory getCategory() {
        return category;
    }

    public void setCategory(AuditCategory category) {
        this.category = category;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public String getAdminNameSnapshot() {
        return adminNameSnapshot;
    }

    public void setAdminNameSnapshot(String adminNameSnapshot) {
        this.adminNameSnapshot = adminNameSnapshot;
    }

    public String getAdminEmailSnapshot() {
        return adminEmailSnapshot;
    }

    public void setAdminEmailSnapshot(String adminEmailSnapshot) {
        this.adminEmailSnapshot = adminEmailSnapshot;
    }

    public String getItemNameSnapshot() {
        return itemNameSnapshot;
    }

    public void setItemNameSnapshot(String itemNameSnapshot) {
        this.itemNameSnapshot = itemNameSnapshot;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public void setPrevHash(String prevHash) {
        this.prevHash = prevHash;
    }

    public String getRowHash() {
        return rowHash;
    }

    public void setRowHash(String rowHash) {
        this.rowHash = rowHash;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }
}
