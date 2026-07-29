package ge.magti.portal.domain;

import java.time.OffsetDateTime;

/**
 * Mirrors models.py's ArticleHistory (models.py:458-483) -- one snapshot
 * row per edit. Plain shape only, no persistence annotations (Phase 1b),
 * same rule as {@link User}.
 *
 * <p>{@link #versionId} is nullable on purpose, matching the Python column
 * exactly: it's the {@code article.version} value the snapshot represents
 * (the version carried *before* the edit that created this row), and rows
 * created before that column existed have it as null -- the UI falls back
 * to list index for those. Not backfilled or defaulted here.
 */
public class ArticleHistory {

    private Long id;
    private Long articleId;
    private String title;
    private String content;
    private OffsetDateTime updatedAt;
    private Long updatedBy;
    private Integer versionId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getArticleId() {
        return articleId;
    }

    public void setArticleId(Long articleId) {
        this.articleId = articleId;
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

    public Integer getVersionId() {
        return versionId;
    }

    public void setVersionId(Integer versionId) {
        this.versionId = versionId;
    }
}
