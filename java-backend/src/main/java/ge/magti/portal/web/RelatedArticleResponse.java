package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RelatedArticleResponse(Long id, String title, @JsonProperty("category_id") Long categoryId, String tags) {
}
