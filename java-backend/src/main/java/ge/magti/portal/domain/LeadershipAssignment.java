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
 * Who leads which group or department (V36).
 *
 * <p>Replaces two things that answer that question badly today:
 * {@code users.role == MANAGER}, which is a role and not a scope and cannot
 * express one person leading two groups; and {@code users.manager_id}, which
 * is populated on zero rows.
 *
 * <p>Scope is {@link #departmentId} XOR {@link #teamId} -- see
 * {@link LeadershipScope} for why it is two real foreign keys rather than a
 * polymorphic pair, and V36 for the CHECK that enforces the XOR in Oracle 19c,
 * which has no SQL boolean type to compare {@code IS NULL} results with.
 *
 * <p>{@link #source} records how the row got here. {@code BACKFILL} rows come
 * from the Phase 2 transitional backfill and are the ones a system admin
 * should review first; {@code AD_SYNC} rows are owned upstream.
 */
@Entity
@Table(name = "leadership_assignments")
public class LeadershipAssignment {

    /** How a row came to exist -- see the class javadoc. */
    public enum Source {
        MANUAL,
        BACKFILL,
        AD_SYNC
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "team_id")
    private Long teamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_type", nullable = false, length = 20)
    private AssignmentType assignmentType;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private Source source = Source.MANUAL;

    /** Derived, never stored: exactly one scope column is set (V36's CHECK). */
    public LeadershipScope scope() {
        return teamId != null ? LeadershipScope.GROUP : LeadershipScope.DEPARTMENT;
    }

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

    public Long getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }

    public Long getTeamId() {
        return teamId;
    }

    public void setTeamId(Long teamId) {
        this.teamId = teamId;
    }

    public AssignmentType getAssignmentType() {
        return assignmentType;
    }

    public void setAssignmentType(AssignmentType assignmentType) {
        this.assignmentType = assignmentType;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(OffsetDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }
}
