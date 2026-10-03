package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.Category;

public record CategoryResponse(
        Long id,
        String name,
        @JsonProperty("parent_id") Long parentId,
        String slug,
        String icon,
        @JsonProperty("pastel_color_class") String pastelColorClass,
        @JsonProperty("is_active") boolean isActive
) {
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(), category.getName(), category.getParentId(), category.getSlug(),
                category.getIcon(), category.getPastelColorClass(), category.isActive());
    }
}
