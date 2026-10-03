package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Team Statistics
 * foundation, distinct from the free-text {@code users.department} string.
 *
 * <p>Deliberately NOT modeled: the {@code members} back-reference.
 * Same reasoning as {@link User}'s omitted {@code team}/
 * {@code manager} object references -- {@link User#getTeamId()} carries the
 * raw foreign key; adding {@code @OneToMany(mappedBy = "team")} here is
 * repository-layer work for whenever something actually needs to query it,
 * not a data-structure decision to make upfront.
 */
@Entity
@Table(name = "teams")
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * No longer {@code unique = true}: V36 drops the global
     * {@code uq_teams_name}, because the target model has five groups per
     * department and group names repeat between departments by design.
     * V37 restores uniqueness as {@code (department_id, name)}.
     */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    /** Nullable until V37; see {@link ge.magti.portal.domain.Department}. */
    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "stable_key", length = 50)
    private String stableKey;

    @Column(name = "ad_external_id", length = 200)
    private String adExternalId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "synced_at")
    private OffsetDateTime syncedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }

    public String getStableKey() {
        return stableKey;
    }

    public void setStableKey(String stableKey) {
        this.stableKey = stableKey;
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

    public OffsetDateTime getSyncedAt() {
        return syncedAt;
    }

    public void setSyncedAt(OffsetDateTime syncedAt) {
        this.syncedAt = syncedAt;
    }
}
