package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors get_my_progress's ad-hoc dict (routers/compliance.py:132-143) -- no formal Pydantic schema in Python. */
public record MyProgressResponse(
        @JsonProperty("total_mandatory") int totalMandatory,
        @JsonProperty("read_completed") int readCompleted,
        int pending,
        int percentage
) {
}
