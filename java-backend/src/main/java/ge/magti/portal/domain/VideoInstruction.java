package ge.magti.portal.domain;

import java.time.OffsetDateTime;

/**
 * Mirrors models.py's VideoInstruction (models.py:240-253, table
 * {@code video_instructions}). Plain shape only, no persistence annotations
 * (Phase 1b) -- same rule as {@link User}.
 *
 * <p>Two asymmetries worth knowing, both carried over unchanged rather than
 * "fixed" to look more like {@link Article}/{@link News}:
 * <ul>
 *   <li>{@link #category} is a free-text label, not a foreign key --
 *       unlike {@link Article#getCategoryId()}, there is no
 *       {@code Category} row behind it.</li>
 *   <li>{@link #archived} is a real stored flag here, whereas
 *       {@link News#isArchived()} is computed from an expiry timestamp --
 *       two different archive designs for two content types in the same
 *       app.</li>
 * </ul>
 *
 * <p>{@link #targetDepartment} is a single value, not a list -- known bug
 * #10 (not yet decided): the visibility filter for this field is an exact
 * string match rather than the prefix-aware rule used elsewhere
 * (routers/videos.py:77), so an operator in a department's sub-group
 * doesn't see a video targeted at the parent department. Nothing to fix
 * yet -- there is no query/filter logic to port until a repository layer
 * exists; flagged here so it isn't missed when that logic is written.
 */
public class VideoInstruction {

    private Long id;
    private String title;
    private String videoUrl;
    private String category;
    private String targetDepartment = "All";
    private OffsetDateTime createdAt;
    private int viewsCount = 0;
    private String tags;
    private boolean archived = false;

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

    public String getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
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

    public int getViewsCount() {
        return viewsCount;
    }

    public void setViewsCount(int viewsCount) {
        this.viewsCount = viewsCount;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }
}
