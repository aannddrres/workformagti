package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Mirrors get_article_views' top-level ad-hoc dict shape (routers/articles.py:1342-1359). */
public record ArticleViewsResponse(
        @JsonProperty("article_id") Long articleId,
        @JsonProperty("current_version") int currentVersion,
        @JsonProperty("filter_version") Integer filterVersion,
        @JsonProperty("total_views") long totalViews,
        @JsonProperty("unique_viewers") long uniqueViewers,
        List<ArticleViewRowResponse> views
) {
}
