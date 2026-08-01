package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Mirrors schemas.py's CategoryCreate (itself CategoryBase, used as-is) --
 * one shared request shape for both create and update, exactly as Python
 * does.
 */
public record CategoryRequest(
        @NotBlank String name,
        @JsonProperty("parent_id") Long parentId,
        String slug,
        String icon,
        @JsonProperty("pastel_color_class") String pastelColorClass,
        @JsonProperty("is_active") Boolean isActive
) {
    public boolean isActiveOrDefault() {
        return isActive == null || isActive;
    }
}
