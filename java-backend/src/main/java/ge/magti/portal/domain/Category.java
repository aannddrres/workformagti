package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@link #parentId} is the raw self-referencing foreign key
 * ({@code parent_id}); the parent/children object graph and the
 * {@code articles} back-reference are repository-layer concerns, not
 * modeled here, same reasoning as {@link User#getTeamId()}.
 */
@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "slug", length = 150)
    private String slug;

    @Column(name = "icon", length = 100)
    private String icon;

    @Column(name = "pastel_color_class", length = 100)
    private String pastelColorClass;

    @Column(name = "is_active")
    private boolean active = true;

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

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getPastelColorClass() {
        return pastelColorClass;
    }

    public void setPastelColorClass(String pastelColorClass) {
        this.pastelColorClass = pastelColorClass;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
