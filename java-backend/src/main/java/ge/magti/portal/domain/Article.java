package ge.magti.portal.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors models.py's Article (models.py:111-186). Plain shape only, no
 * persistence annotations (Phase 1b) -- same rule as {@link User}. History
 * ({@code ArticleHistory}), read receipts, and quiz questions are separate
 * tables, deliberately not modeled as part of this class -- later Content
 * sub-steps, same reasoning as {@link User} deferring {@code Team}.
 *
 * <p>Not ported: the {@code category_name} and {@code read_time} Python
 * {@code @property}s (models.py:163-186). Both are response-shaping
 * (a joined display name; an estimated reading time from a word count) for
 * whatever serves the API response, not part of the entity's own shape --
 * they belong wherever that response gets assembled, not here.
 *
 * <p><b>Department targeting is two coexisting mechanisms today, on
 * purpose, mid-migration inside the Python app itself</b> (models.py:156-171,
 * 189-198; routers/articles.py:84,150-153,230-238):
 * <ul>
 *   <li>{@link #targetDepartments} -- the actual, authoritative list,
 *       backed by the {@code article_target_departments} junction table.
 *       Every real visibility/filter check in routers/articles.py reads
 *       this, not the field below.</li>
 *   <li>{@link #targetDepartment} -- the legacy single-value column.
 *       routers/articles.py's own comment calls it out as "kept in sync
 *       for not-yet-migrated readers (e.g. _notify's SSE payload) during
 *       the transition window" -- it is written (best-effort: "All" if the
 *       list contains it, else the first department) but never read for
 *       access control.</li>
 * </ul>
 * Both are carried over here unchanged, exactly as coexisting, rather than
 * collapsed to one -- logged as additional finding #19 in the migration
 * doc since it wasn't in the original known-issues list and isn't this
 * initiative's to resolve unilaterally.
 */
public class Article {

    private Long id;
    private String title;
    private String content;
    private Long categoryId;
    private String tags;
    private String targetDepartment = "All";
    private List<String> targetDepartments = new ArrayList<>();
    private String audienceProfile = "all";
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    /**
     * Known bug #2, decided fix (2026-07-29): editing an article's quiz
     * today does not bump this (routers/articles.py:730-765), even though
     * the read-receipt quiz-gate checks against it (:1024) -- so a
     * reader who already passed an old quiz is never asked to retake a
     * changed one. Whoever writes the quiz-update service method on this
     * entity should increment {@code version} there; not fixed here since
     * there is no service/repository layer yet.
     */
    private int version = 1;
    private Long authorId;
    private String status = "draft";
    private String youtubeId;
    private OffsetDateTime publishedAt;
    private String attachmentUrl;
    private OffsetDateTime lastVerifiedAt;
    private boolean visibleToTechInfo = true;
    private boolean visibleToServiceCenter = false;
    private boolean isDraft = true;
    private boolean quizEnabled = false;

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

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getTargetDepartment() {
        return targetDepartment;
    }

    public void setTargetDepartment(String targetDepartment) {
        this.targetDepartment = targetDepartment;
    }

    public List<String> getTargetDepartments() {
        return targetDepartments;
    }

    public void setTargetDepartments(List<String> targetDepartments) {
        this.targetDepartments = targetDepartments;
    }

    public String getAudienceProfile() {
        return audienceProfile;
    }

    public void setAudienceProfile(String audienceProfile) {
        this.audienceProfile = audienceProfile;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getYoutubeId() {
        return youtubeId;
    }

    public void setYoutubeId(String youtubeId) {
        this.youtubeId = youtubeId;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(OffsetDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getAttachmentUrl() {
        return attachmentUrl;
    }

    public void setAttachmentUrl(String attachmentUrl) {
        this.attachmentUrl = attachmentUrl;
    }

    public OffsetDateTime getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public void setLastVerifiedAt(OffsetDateTime lastVerifiedAt) {
        this.lastVerifiedAt = lastVerifiedAt;
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

    public boolean isDraft() {
        return isDraft;
    }

    public void setDraft(boolean draft) {
        isDraft = draft;
    }

    public boolean isQuizEnabled() {
        return quizEnabled;
    }

    public void setQuizEnabled(boolean quizEnabled) {
        this.quizEnabled = quizEnabled;
    }
}
