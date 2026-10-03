package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.article.ArticleVisibility;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One shared request shape for article create and update.
 *
 * <p>{@code author_id} is deliberately not a field here: create always sets
 * it to the authenticated editor's id and update ignores it
 * -- client input is discarded either way, so there is nothing for a
 * request DTO to carry. {@code last_verified_at} is the opposite case --
 * create applies it as sent (not popped), update ignores it -- so it stays
 * here and each handler decides whether to read it.
 */
public record ArticleRequest(
        // Each limit is its column's (CHAR semantics). Past it the insert
        // failed in Oracle and the editor saw "unexpected error" (attack
        // tests, 2026-10-02); content's 5 MB is a hundred times the largest
        // real article (49 KB) and stops a 30 MB body that every search
        // result then carried.
        @NotBlank @Size(max = 500, message = "სათაური 500 სიმბოლოზე გრძელი ვერ იქნება") String title,
        @NotNull @Size(max = MAX_CONTENT_CHARS, message = CONTENT_TOO_LONG) String content,
        @NotNull @JsonProperty("category_id") Long categoryId,
        @Size(max = 500, message = "თეგები ჯამში 500 სიმბოლოზე გრძელი ვერ იქნება") String tags,
        @NotEmpty @JsonProperty("target_departments") List<String> targetDepartments,
        // null/blank is allowed (statusOrDefault() then applies "draft"); an
        // unknown value is not. Without this a single create/update accepted
        // any string -- e.g. a "pubished" typo -- and, because only the exact
        // value "published" is reader-visible, the article then silently
        // vanished from operators with no error. bulk-status already validates
        // (ArticleBulkStatusRequest); this closes the same gap on the single
        // path. "scheduled" is valid here (unlike bulk, which has no date to
        // schedule for); "trashed" is not -- that state is reached by DELETE,
        // never by setting status.
        // The trailing "?" lets an empty string through as well as null, so
        // statusOrDefault() keeps owning the blank-to-"draft" rule; only a
        // present-but-unknown value is rejected.
        @Pattern(regexp = STATUS_PATTERN, message = STATUS_MESSAGE)
        String status,
        @Size(max = 50, message = "YouTube-ის id 50 სიმბოლოზე გრძელი ვერ იქნება") @JsonProperty("youtube_id") String youtubeId,
        @JsonProperty("published_at") OffsetDateTime publishedAt,
        @Size(max = 1000, message = "მიმაგრებული ფაილის ბმული 1000 სიმბოლოზე გრძელი ვერ იქნება") @JsonProperty("attachment_url") String attachmentUrl,
        @JsonProperty("last_verified_at") OffsetDateTime lastVerifiedAt,
        @Size(max = 20, message = "აუდიტორიის პროფილი 20 სიმბოლოზე გრძელი ვერ იქნება") @JsonProperty("audience_profile") String audienceProfile,
        @JsonProperty("visible_to_tech_info") Boolean visibleToTechInfo,
        @JsonProperty("visible_to_service_center") Boolean visibleToServiceCenter,
        @JsonProperty("is_draft") Boolean isDraft,
        // "notify_operators" was a field here until 2026-09-29. It fed the old
        // stack's real-time broadcast, which was never ported, so nothing read
        // it; a client that still sends it is ignored, not refused.
        @JsonProperty("quiz_enabled") Boolean quizEnabled,
        // The lock_version the editor loaded (ArticleResponse). Optional: a
        // caller that does not send it -- a seeder, the importer -- is not
        // checked. The Angular editor always sends it.
        @JsonProperty("lock_version") Integer lockVersion
) {
    /** Shared with PATCH .../autosave, which reads a raw map and so cannot use the annotation. */
    /** Shared with news and both autosave endpoints. */
    public static final int MAX_CONTENT_CHARS = 5_000_000;
    public static final String CONTENT_TOO_LONG = "ტექსტი 5 მილიონ სიმბოლოზე გრძელი ვერ იქნება";
    public static final String STATUS_PATTERN = "(draft|published|scheduled|archived)?";
    public static final String STATUS_MESSAGE = "სტატუსი უნდა იყოს draft, published, scheduled ან archived";

    public String statusOrDefault() {
        return (status == null || status.isBlank()) ? "draft" : status;
    }

    public String audienceProfileOrDefault() {
        return (audienceProfile == null || audienceProfile.isBlank()) ? "all" : audienceProfile;
    }

    public boolean visibleToTechInfoOrDefault() {
        return visibleToTechInfo == null || visibleToTechInfo;
    }

    public boolean visibleToServiceCenterOrDefault() {
        return visibleToServiceCenter != null && visibleToServiceCenter;
    }

    /**
     * Absent means "not a draft" when the status asks for readers, and "a
     * draft" otherwise.
     *
     * <p>This used to be an unconditional {@code true}, independent of status,
     * and that is the trap it now closes. {@code POST /api/articles} with
     * {@code status: "published"} and no {@code is_draft} produced a row that
     * every reader list hid — {@code is_draft} is the personal-autosave flag,
     * so it hides a row from everyone but its author — while the caller had
     * plainly asked for it to be published. Nothing reported an error; the
     * article simply was not there. That is the same shape as the incident
     * that left 122 imported articles visible to one account, and
     * {@code bulkSetArticleStatus} already resolves it the same way, clearing
     * the flag when it publishes.
     *
     * <p>The Angular editor always sends the field
     * ({@code article-edit-drawer.ts}), so nothing in the product changes;
     * what changes is what a seeder, an import or a screen written later gets
     * when it forgets.
     */
    public boolean isDraftOrDefault() {
        if (isDraft != null) {
            return isDraft;
        }
        return !ArticleVisibility.isPublishedByLifecycle(statusOrDefault(), publishedAt);
    }

    /**
     * Refuses the one combination the default above cannot rescue: an
     * explicit {@code is_draft: true} together with a status that asks for
     * readers.
     *
     * <p>Coercing that would be guessing at which of the two the caller meant.
     * Refusing says so, and a 400 here is strictly better than the alternative
     * this endpoint had until 2026-09-06 — a stored row that claims to be
     * published, appears in no list, and whose attachments were served by
     * {@code /uploads/{filename}} regardless.
     *
     * <p>A draft that is merely <i>scheduled</i> for a future moment is not
     * this case and stays allowed: it is not reader-visible yet, so there is
     * no contradiction to refuse.
     */
    @AssertTrue(message = "დაუშვებელია პირადი მონახაზი (is_draft) გამოქვეყნებულ სტატუსთან ერთად — "
            + "ან გამორთეთ მონახაზი, ან დატოვეთ სტატუსი draft")
    @JsonIgnore
    public boolean isDraftAndStatusConsistent() {
        return !Boolean.TRUE.equals(isDraft)
                || !ArticleVisibility.isPublishedByLifecycle(statusOrDefault(), publishedAt);
    }

    public boolean quizEnabledOrDefault() {
        return quizEnabled != null && quizEnabled;
    }

    /** "All" wins outright, else the first pick stands in. */
    public String legacyTargetDepartment() {
        return targetDepartments.contains("All") ? "All" : targetDepartments.get(0);
    }
}
