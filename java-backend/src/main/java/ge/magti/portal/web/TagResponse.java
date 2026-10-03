package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.Tag;

import java.time.OffsetDateTime;

public record TagResponse(
        Long id,
        String name,
        @JsonProperty("created_at") OffsetDateTime createdAt
) {
    public static TagResponse from(Tag tag) {
        return new TagResponse(tag.getId(), tag.getName(), tag.getCreatedAt());
    }
}
