package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.Favorite;

/** Mirrors schemas.py's FavoriteResponse. */
public record FavoriteResponse(
        Long id,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("item_type") String itemType,
        @JsonProperty("item_id") Long itemId,
        @JsonProperty("item_title") String itemTitle
) {
    public static FavoriteResponse from(Favorite favorite, String itemTitle) {
        // Python: `title or f"მასალა #{fav.item_id}"` -- falls through on
        // both None and "" (SQLAlchemy/Pydantic strings are never blank by
        // schema here, but matched exactly rather than assumed).
        String resolved = (itemTitle == null || itemTitle.isEmpty()) ? ("მასალა #" + favorite.getItemId()) : itemTitle;
        return new FavoriteResponse(favorite.getId(), favorite.getUserId(), favorite.getItemType(),
                favorite.getItemId(), resolved);
    }
}
