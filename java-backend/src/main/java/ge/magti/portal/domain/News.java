package ge.magti.portal.domain;

import ge.magti.portal.util.TbilisiTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;

/**
 * A news item. Unlike {@link Article}, News
 * has no multi-department junction table -- just the one
 * {@link #targetDepartment} column.
 *
 * <p>{@link #isArchived()} is computed from
 * {@link #expiresAt}, not a stored column. The migration doc's functional
 * matrix flags this as an open schema question for Oracle -- computed
 * field vs. a real column -- not decided here; this class only preserves
 * today's actual behavior so nothing changes silently either way.
 */
@Entity
@Table(name = "news")
@SQLRestriction("trashed_at IS NULL")
public class News {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Lob
    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "target_department", length = 200)
    private String targetDepartment = "All";

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "attachment_url", length = 1000)
    private String attachmentUrl;

    @Column(name = "version")
    private int version = 1;

    @Column(name = "visible_to_tech_info")
    private boolean visibleToTechInfo = true;

    @Column(name = "visible_to_service_center")
    private boolean visibleToServiceCenter = false;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "is_draft")
    private boolean isDraft = true;

    @Column(name = "author_id")
    private Long authorId;

    public boolean isArchived() {
        return expiresAt != null && expiresAt.isBefore(TbilisiTime.now());
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTargetDepartment() {
        return targetDepartment;
    }

    public void setTargetDepartment(String targetDepartment) {
        this.targetDepartment = targetDepartment;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getAttachmentUrl() {
        return attachmentUrl;
    }

    public void setAttachmentUrl(String attachmentUrl) {
        this.attachmentUrl = attachmentUrl;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public boolean isVisibleToTechInfo() {
        return visibleToTechInfo;
    }

    public void setVisibleToTechInfo(boolean visibleToTechInfo) {
        this.visibleToTechInfo = visibleToTechInfo;
    }

    public boolean isVisibleToServiceCenter() {
        return visibleToServiceCenter;
    }

    public void setVisibleToServiceCenter(boolean visibleToServiceCenter) {
        this.visibleToServiceCenter = visibleToServiceCenter;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isDraft() {
        return isDraft;
    }

    public void setDraft(boolean draft) {
        isDraft = draft;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }
}
