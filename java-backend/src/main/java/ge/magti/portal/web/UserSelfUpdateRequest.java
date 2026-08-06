package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/** Mirrors schemas.py's UserSelfUpdate (schemas.py:503-508). */
public record UserSelfUpdateRequest(
        @NotBlank String name,
        String phone,
        String position,
        @JsonProperty("card_style") String cardStyle
) {
}
