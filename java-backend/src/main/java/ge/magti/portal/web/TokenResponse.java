package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** (access_token, token_type) -- snake_case on the wire. */
public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType) {
}
