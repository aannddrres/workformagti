package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MyProgressResponse(
        @JsonProperty("total_mandatory") int totalMandatory,
        @JsonProperty("read_completed") int readCompleted,
        int pending,
        int percentage
) {
}
