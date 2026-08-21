package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One administrator's explicit decision about one permission for one user (V36).
 *
 * <p>{@code users.permissions} is a flat JSON list and therefore cannot say
 * whether a permission is present because the role grants it or because
 * somebody granted it to this person. Without that distinction a role change
 * has to either wipe explicit grants or keep grants the new role never had --
 * both wrong, and the first is what {@code bulkReassignRoles} does today.
 *
 * <p><b>{@code INHERIT} is not a value here.</b> The absence of a row is
 * inherit, so "revert to the role default" is a delete and cannot drift from
 * {@link Permission#defaultsFor}. Storing it would add a third state that has
 * to be kept in agreement with the role catalog forever.
 *
 * <p>Nothing reads this table yet -- Phase 6 does, when
 * {@code PUT /api/users/{id}/permissions} stops being a flat replace.
 */
@Entity
@Table(name = "user_permission_overrides")
public class UserPermissionOverride {

    /** The two states worth storing; see the class javadoc on the missing third. */
    public enum State {
        ALLOW,
        DENY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "permission", nullable = false, length = 50)
    private String permission;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 10)
    private State state;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

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

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }
}
