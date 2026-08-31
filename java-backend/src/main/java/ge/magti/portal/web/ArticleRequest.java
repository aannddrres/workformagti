package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Mirrors schemas.py's ArticleCreate/ArticleUpdate -- one shared shape for
 * both, exactly as Python does (ArticleUpdate adds nothing to ArticleCreate).
 *
 * <p>{@code author_id} is deliberately not a field here: Python's schema has
 * it, but create_article always overwrites it with the authenticated admin's
 * id (routers/articles.py:231) and update_article explicitly pops/ignores it
 * (:342) -- client input is discarded either way, so there is nothing for a
 * request DTO to carry. {@code last_verified_at} is the opposite case --
 * create applies it as sent (not popped), update ignores it -- so it stays
 * here and each handler decides whether to read it.
 */
public record ArticleRequest(
        @NotBlank String title,
        @NotNull String content,
        @NotNull @JsonProperty("category_id") Long categoryId,
        String tags,
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
        @Pattern(regexp = "(draft|published|scheduled|archived)?",
                message = "სტატუსი უნდა იყოს draft, published, scheduled ან archived")
        String status,
        @JsonProperty("youtube_id") String youtubeId,
        @JsonProperty("published_at") OffsetDateTime publishedAt,
        @JsonProperty("attachment_url") String attachmentUrl,
        @JsonProperty("last_verified_at") OffsetDateTime lastVerifiedAt,
        @JsonProperty("audience_profile") String audienceProfile,
        @JsonProperty("visible_to_tech_info") Boolean visibleToTechInfo,
        @JsonProperty("visible_to_service_center") Boolean visibleToServiceCenter,
        @JsonProperty("is_draft") Boolean isDraft,
        @JsonProperty("quiz_enabled") Boolean quizEnabled,
        @JsonProperty("notify_operators") Boolean notifyOperators
) {
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

    public boolean isDraftOrDefault() {
        return isDraft == null || isDraft;
    }

    public boolean quizEnabledOrDefault() {
        return quizEnabled != null && quizEnabled;
    }

    public boolean notifyOperatorsOrDefault() {
        return notifyOperators != null && notifyOperators;
    }

    /** routers/articles.py:234 / :347 -- "All" wins outright, else the first pick stands in. */
    public String legacyTargetDepartment() {
        return targetDepartments.contains("All") ? "All" : targetDepartments.get(0);
    }
}
