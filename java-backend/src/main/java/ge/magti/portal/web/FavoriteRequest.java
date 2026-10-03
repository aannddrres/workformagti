package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** Mirrors schemas.py's FavoriteCreate (= FavoriteBase). */
public record FavoriteRequest(
        // Only what can be a favourite. Made-up types and negative ids were
        // stored, and an over-long type was a 500 (simulation, 2026-10-01).
        @NotBlank @Pattern(regexp = "article|news|video", message = "რჩეული შეიძლება იყოს სტატია, სიახლე ან ვიდეო")
        @JsonProperty("item_type") String itemType,
        @NotNull @Positive @JsonProperty("item_id") Long itemId
) {
}
