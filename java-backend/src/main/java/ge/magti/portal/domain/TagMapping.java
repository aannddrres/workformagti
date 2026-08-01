package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Mirrors models.py's TagMapping (models.py:280-293) -- polymorphic
 * many-to-many link between a {@link Tag} and a tagged item ({@code
 * item_type}: "article" | "video"), mirroring the {@link Favorite}/{@link
 * RequiredReading} polymorphic pattern.
 */
@Entity
@Table(name = "tags_mapping", uniqueConstraints = @UniqueConstraint(
        name = "uq_tag_mapping_item", columnNames = {"tag_id", "item_type", "item_id"}))
public class TagMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    @Column(name = "item_type", nullable = false, length = 20)
    private String itemType;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTagId() {
        return tagId;
    }

    public void setTagId(Long tagId) {
        this.tagId = tagId;
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
}
