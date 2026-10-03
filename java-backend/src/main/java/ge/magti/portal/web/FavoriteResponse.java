package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.Favorite;

public record FavoriteResponse(
        Long id,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("item_type") String itemType,
        @JsonProperty("item_id") Long itemId,
        @JsonProperty("item_title") String itemTitle
) {
    public static FavoriteResponse from(Favorite favorite, String itemTitle) {
        // Falls back to "მასალა #<item id>" on both null and "".
        String resolved = (itemTitle == null || itemTitle.isEmpty()) ? ("მასალა #" + favorite.getItemId()) : itemTitle;
        return new FavoriteResponse(favorite.getId(), favorite.getUserId(), favorite.getItemType(),
                favorite.getItemId(), resolved);
    }
}
