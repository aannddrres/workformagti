package ge.magti.portal.domain;

import java.time.OffsetDateTime;

/**
 * Mirrors models.py's AuditLog (models.py:336-376). Plain shape only, no
 * persistence annotations (Phase 1b), same rule as {@link User}.
 *
 * <p>{@link #prevHash}/{@link #rowHash}/{@link #ipAddress}/{@link #userAgent}
 * are populated by a Postgres-only {@code BEFORE INSERT} trigger
 * (migrate.py's {@code AUDIT_CHAIN_STATEMENTS}) -- never set from
 * application code on the Python side, and not settable here either; a
 * plain domain object has nowhere to run that trigger. NULL on this class
 * means "not yet chained by a trigger", exactly as it does in Python, not
 * "tampered".
 *
 * <p><b>This is deliberately as far as this step goes.</b> The hash-chain
 * mechanism itself -- the actual security-critical, Oracle-specific
 * redesign -- is not attempted here; see the migration doc's §1a for the
 * freshly re-verified analysis of what it would take, and why none of it
 * can be honestly written without a real Oracle connection to test
 * against.
 */
public class AuditLog {

    private Long id;
    private Long adminId;
    private String action;
    private String itemType;
    private Long itemId;
    private OffsetDateTime timestamp;
    private AuditCategory category;
    private String details;
    private String adminNameSnapshot;
    private String adminEmailSnapshot;
    private String itemNameSnapshot;
    private String prevHash;
    private String rowHash;
    private String ipAddress;
    private String userAgent;

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
