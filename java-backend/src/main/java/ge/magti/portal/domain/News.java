package ge.magti.portal.domain;

import ge.magti.portal.util.TbilisiTime;

import java.time.OffsetDateTime;

/**
 * Mirrors models.py's News (models.py:58-79). Plain shape only, no
 * persistence annotations (Phase 1b) -- same rule as {@link User}. Unlike
 * {@link Article}, News has no multi-department junction table -- just the
 * one {@link #targetDepartment} column.
 *
 * <p>{@link #isArchived()} mirrors Python's {@code is_archived}
 * {@code @property} (models.py:77-79) exactly: computed from
 * {@link #expiresAt}, not a stored column. The migration doc's functional
 * matrix flags this as an open schema question for Oracle -- computed
 * field vs. a real column -- not decided here; this class only preserves
 * today's actual behavior so nothing changes silently either way.
 */
public class News {

    private Long id;
    private String title;
    private String content;
    private String targetDepartment = "All";
    private OffsetDateTime createdAt;
    private String attachmentUrl;
    private int version = 1;
    private boolean visibleToTechInfo = true;
    private boolean visibleToServiceCenter = false;
    private OffsetDateTime expiresAt;
    private boolean isDraft = true;
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
