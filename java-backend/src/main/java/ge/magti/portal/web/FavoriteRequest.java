package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Mirrors schemas.py's FavoriteCreate (= FavoriteBase). */
public record FavoriteRequest(
        @NotBlank @JsonProperty("item_type") String itemType,
        @NotNull @JsonProperty("item_id") Long itemId
) {
}
