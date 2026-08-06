package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per distinct 3-character trigram found anywhere in one entity's
 * searchable text. See {@link ge.magti.portal.search.TrigramIndexer} and
 * {@link ge.magti.portal.search.SearchReindexService} for how this table is
 * built and used -- it is a candidate pre-filter only, never the sole source
 * of truth for a match.
 *
 * <p>{@code entityType} is one of "ARTICLE"/"NEWS"/"VIDEO"; {@code entityId}
 * is deliberately not a foreign key, the same polymorphic-reference shape
 * {@link AuditLog#getItemType()}/{@link AuditLog#getItemId()} already uses,
 * since it points at three different tables depending on the type.
 */
@Entity
@Table(name = "search_trigrams")
public class SearchTrigram {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "entity_type", nullable = false, length = 10)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "trigram", nullable = false, length = 3)
    private String trigram;

    public SearchTrigram() {
    }

    public SearchTrigram(String entityType, Long entityId, String trigram) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.trigram = trigram;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public void setEntityId(Long entityId) {
        this.entityId = entityId;
    }

    public String getTrigram() {
        return trigram;
    }

    public void setTrigram(String trigram) {
        this.trigram = trigram;
    }
}
