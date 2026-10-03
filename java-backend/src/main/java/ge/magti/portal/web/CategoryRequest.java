package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One shared request shape for both category create and update.
 */
public record CategoryRequest(
        @NotBlank @Size(max = 200) String name,
        @JsonProperty("parent_id") Long parentId,
        @Size(max = 150) String slug,
        @Size(max = 100) String icon,
        @Size(max = 100) @JsonProperty("pastel_color_class") String pastelColorClass,
        @JsonProperty("is_active") Boolean isActive
) {
    public boolean isActiveOrDefault() {
        return isActive == null || isActive;
    }
}
