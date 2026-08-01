package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors get_related_articles' ad-hoc dict shape (routers/articles.py:1636-1644) -- no formal Pydantic schema in Python either. */
public record RelatedArticleResponse(Long id, String title, @JsonProperty("category_id") Long categoryId, String tags) {
}
