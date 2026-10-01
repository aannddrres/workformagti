package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.Transient;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors models.py's Article (models.py:111-186). {@link ArticleHistory},
 * read receipts, and quiz questions are separate tables, deliberately not
 * modeled as part of this class, same reasoning as {@link User} deferring
 * {@link Team} as an object reference.
 *
 * <p>{@code category_name} remains response-shaping only. {@code read_time}
 * is a database-maintained derived scalar: V45 backfills it and an Oracle
 * trigger keeps it synchronized with the content CLOB, allowing list
 * projections to preserve the response value without loading the CLOB.
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
@Entity
@Table(name = "articles")
@SQLRestriction("trashed_at IS NULL")
public class Article {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Lob
    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "tags", length = 500)
    private String tags;

    @Column(name = "target_department", length = 200)
    private String targetDepartment = "All";

    // @Transient: not a column on this table at all -- the authoritative
    // list lives in the article_target_departments junction table (see
    // ArticleTargetDepartment), populated by a repository query, not a
    // JPA relationship yet (that's later work once queries exist).
    @Transient
    private List<String> targetDepartments = new ArrayList<>();

    /**
     * Legacy, together with {@code visible_to_tech_info} and
     * {@code visible_to_service_center}: stored, returned and carried through
     * edits, but no rule reads any of the three and no screen shows them.
     * Who may read an article is target_departments alone (ArticleVisibility);
     * the old roles these flags served never existed here
     * (ArticleQueryService's javadoc). Do not build on them without a
     * decision to bring them back (audit 2026-10-01).
     */
    @Column(name = "audience_profile", length = 20)
    private String audienceProfile = "all";

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
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
    @Column(name = "version")
    private int version = 1;

    /**
     * Optimistic lock (audit BL-11). Deliberately NOT {@link #version}:
     * that one is the business version -- what read receipts and the quiz
     * gate key on, what the history list shows, what a restore rewinds.
     * Tying concurrency control to a number the application increments and
     * displays on purpose would make every version bump look like a
     * conflict.
     *
     * <p>Managed entirely by Hibernate; nothing in application code sets it.
     * Its job is to make the second of two simultaneous
     * saves fail cleanly at the UPDATE, instead of both computing the same
     * {@code version + 1} and colliding on
     * {@code ux_article_history_article_version} afterwards -- which
     * surfaced as an opaque 500 with the edit lost.
     *
     * <p>It is read for one more thing: {@code PUT /api/articles/{id}}
     * compares it with the copy the editor loaded. {@code @Version} alone
     * catches two saves in the same instant only -- two people with the
     * editor open for minutes both saved, and the second silently won
     * (audit 2026-10-01).
     */
    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    @Column(name = "author_id")
    private Long authorId;

    @Column(name = "status", length = 30)
    private String status = "draft";

    @Column(name = "youtube_id", length = 50)
    private String youtubeId;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "attachment_url", length = 1000)
    private String attachmentUrl;

    @Column(name = "last_verified_at")
    private OffsetDateTime lastVerifiedAt;

    @Column(name = "visible_to_tech_info")
    private boolean visibleToTechInfo = true;

    @Column(name = "visible_to_service_center")
    private boolean visibleToServiceCenter = false;

    @Column(name = "is_draft")
    private boolean isDraft = true;

    @Column(name = "quiz_enabled")
    private boolean quizEnabled = false;

    @Column(name = "read_time", nullable = false, insertable = false, updatable = false)
    private int readTime = 1;

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

    public int getLockVersion() {
        return lockVersion;
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

    public int getReadTime() {
        return readTime;
    }
}
