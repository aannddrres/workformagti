package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * One shared request shape for news create and update.
 *
 * <p><b>Two confirmed live bugs, flagged to and fixed per the user's
 * explicit choice, 2026-08-04</b> (see docs/archive/migration/JAVA_ORACLE_ANGULAR_MIGRATION.md's
 * News section for the full writeup):
 * <ul>
 *   <li>{@code author_id} is not a field here at all -- create always sets
 *   it to the authenticated editor's id, and the original update silently
 *   nulled author_id on every edit, since the admin form never sends this
 *   field.</li>
 *   <li>{@link #isDraftOrDefaultForCreate()} defaults to {@code false}
 *   (published) when absent, the opposite of the original default of
 *   {@code true}. Confirmed live: the real "add news" admin form never
 *   sends {@code is_draft}, so every news item ever created through the
 *   actual UI has been created as an invisible draft -- the 5 seed-data
 *   items visible in the app were inserted directly by a seed script with
 *   {@code is_draft=False}, bypassing this endpoint entirely, which is why
 *   the defect hasn't been noticed before now.</li>
 * </ul>
 * On <b>update</b>, {@code isDraft}/{@code expiresAt} are left untouched on
 * the existing row (see {@code NewsController#updateNews}) rather than
 * reset to a default -- the edit form doesn't manage either field, so a
 * full-replace would silently un-publish or clear the expiry on every
 * unrelated edit (e.g. fixing a typo), the same root cause as the
 * author_id bug above, just reaching two more fields.
 * {@link #isDraftOrDefaultForCreate()} is therefore only meant to be called
 * from the create path.
 */
public record NewsRequest(
        // Column limits; see ArticleRequest (attack tests, 2026-10-02).
        @NotBlank @Size(max = 500, message = "სათაური 500 სიმბოლოზე გრძელი ვერ იქნება") String title,
        @NotNull @Size(max = ArticleRequest.MAX_CONTENT_CHARS, message = ArticleRequest.CONTENT_TOO_LONG) String content,
        @Size(max = 200, message = "დეპარტამენტი 200 სიმბოლოზე გრძელი ვერ იქნება") @JsonProperty("target_department") String targetDepartment,
        @Size(max = 1000, message = "მიმაგრებული ფაილის ბმული 1000 სიმბოლოზე გრძელი ვერ იქნება") @JsonProperty("attachment_url") String attachmentUrl,
        @JsonProperty("visible_to_tech_info") Boolean visibleToTechInfo,
        @JsonProperty("visible_to_service_center") Boolean visibleToServiceCenter,
        @JsonProperty("expires_at") OffsetDateTime expiresAt,
        @JsonProperty("is_draft") Boolean isDraft,
        // The version the editor loaded. Optional: callers that do not send
        // it are not checked; the Angular editor always sends it, so a save
        // over someone else's newer one is refused rather than silently lost
        // (audit 2026-10-01).
        @JsonProperty("version") Integer expectedVersion
) {
    public String targetDepartmentOrDefault() {
        return (targetDepartment == null || targetDepartment.isBlank()) ? "All" : targetDepartment;
    }

    public boolean visibleToTechInfoOrDefault() {
        return visibleToTechInfo == null || visibleToTechInfo;
    }

    public boolean visibleToServiceCenterOrDefault() {
        return visibleToServiceCenter != null && visibleToServiceCenter;
    }

    public boolean isDraftOrDefaultForCreate() {
        return isDraft != null && isDraft;
    }
}
