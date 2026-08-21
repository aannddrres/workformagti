package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One of the three official departments (V36), normalising what
 * {@code users.department} carries as free text today.
 *
 * <p>{@link #stableKey} is the identity every foreign key hangs off, and it is
 * ASCII on purpose. {@link #name} is the Georgian display string and must stay
 * byte-identical to {@code DepartmentBuckets.WHITELIST} while the free-text
 * column still exists -- the transitional mapper matches on it. Once AD owns
 * this table, {@link #adExternalId} carries AD's own identifier and a rename
 * upstream changes {@link #name} without touching anything that points here.
 */
@Entity
@Table(name = "departments")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "stable_key", nullable = false, length = 50)
    private String stableKey;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "ad_external_id", length = 200)
    private String adExternalId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "synced_at")
    private OffsetDateTime syncedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStableKey() {
        return stableKey;
    }

    public void setStableKey(String stableKey) {
        this.stableKey = stableKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAdExternalId() {
        return adExternalId;
    }

    public void setAdExternalId(String adExternalId) {
        this.adExternalId = adExternalId;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public OffsetDateTime getSyncedAt() {
        return syncedAt;
    }

    public void setSyncedAt(OffsetDateTime syncedAt) {
        this.syncedAt = syncedAt;
    }
}
