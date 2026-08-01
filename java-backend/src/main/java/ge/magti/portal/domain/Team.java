package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Mirrors models.py's Team (models.py:256-266) -- Team Statistics
 * foundation, distinct from the free-text {@code users.department} string.
 *
 * <p>Deliberately NOT modeled: the {@code members} back-reference
 * (models.py:266). Same reasoning as {@link User}'s omitted {@code team}/
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

    @Column(name = "name", nullable = false, unique = true, length = 200)
    private String name;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

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
}
