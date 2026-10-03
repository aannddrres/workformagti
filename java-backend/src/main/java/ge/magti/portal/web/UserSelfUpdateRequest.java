package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserSelfUpdateRequest(
        // Optional, and only accepted unchanged: the company directory owns a
        // person's name and resets it at every sign-in. Taken as sent, an
        // operator renamed themselves "სისტემური ადმინისტრატორი" for every
        // screen and export (simulation, 2026-10-01).
        String name,
        @Size(max = 30) String phone,
        @Size(max = 200) String position,
        // A display preference, not text anyone reads: a short token.
        @Pattern(regexp = "[a-z][a-z0-9_-]{0,49}", message = "ბარათის სტილი არასწორია")
        @JsonProperty("card_style") String cardStyle
) {
}
