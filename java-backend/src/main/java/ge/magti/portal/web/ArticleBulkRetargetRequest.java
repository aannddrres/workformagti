package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Re-file many articles: a different category, a different audience, or both.
 *
 * <p>The import brings 122 articles in with the categories and departments the
 * old portal had. Some of those will be wrong for the new one, and correcting
 * them one drawer at a time is the difference between a morning and a week.
 *
 * <p>Both fields are optional and null means "leave alone", so changing an
 * audience does not silently re-file a category. An EMPTY department list is
 * not the same as null -- it would leave articles nobody can see -- so it is
 * rejected rather than treated as a clear.
 */
public record ArticleBulkRetargetRequest(
        @NotEmpty
        @Size(max = 500, message = "ერთ ჯერზე მაქსიმუმ 500 მასალა")
        List<Long> ids,

        /** Null leaves the category as it is. */
        @JsonProperty("category_id") Long categoryId,

        /** Null leaves the audience as it is; empty is refused. */
        @JsonProperty("target_departments")
        @Size(max = 50, message = "მაქსიმუმ 50 დეპარტამენტი")
        List<String> targetDepartments
) {
    public boolean changesNothing() {
        return categoryId == null && targetDepartments == null;
    }
}
