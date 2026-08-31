package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Move many articles to one editorial status at once.
 *
 * <p>The general form of {@code bulk-archive}, which could only swing between
 * archived and published. That was enough while the only bulk need was tidying
 * up; it is not enough to release 122 imported articles a few at a time, which
 * needs {@code draft} as a holding state on the way in and out.
 *
 * <p>{@code scheduled} is deliberately absent. A scheduled article needs a
 * {@code published_at} to be scheduled FOR, and one date applied to a hundred
 * articles is not a schedule -- it is a publish with extra steps.
 */
public record ArticleBulkStatusRequest(
        @NotEmpty
        // Bounded so one request cannot be turned into an unbounded write.
        // 500 is comfortably above the 122-article import this exists for.
        @Size(max = 500, message = "ერთ ჯერზე მაქსიმუმ 500 მასალა")
        List<Long> ids,

        @NotNull
        @Pattern(regexp = "draft|published|archived", message = "სტატუსი უნდა იყოს draft, published ან archived")
        String status
) {
}
