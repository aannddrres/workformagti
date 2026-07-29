package ge.magti.portal.domain;

/**
 * Mirrors models.py's Category (models.py:96-108). Plain shape only, no
 * persistence annotations (Phase 1b) -- same rule as {@link User}.
 *
 * <p>{@link #parentId} is the raw self-referencing foreign key
 * (models.py's {@code parent_id}); the parent/children object graph and the
 * {@code articles} back-reference are repository-layer concerns, not
 * modeled here, same reasoning as {@link User#getTeamId()}.
 */
public class Category {

    private Long id;
    private String name;
    private Long parentId;
    private String slug;
    private String icon;
    private String pastelColorClass;
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
