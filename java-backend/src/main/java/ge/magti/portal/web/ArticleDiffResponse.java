package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors get_article_diff / _diff_ordered_by_version's return dict (routers/articles.py:568-630). */
public record ArticleDiffResponse(
        String html,
        int added,
        int removed,
        @JsonProperty("base_version") int baseVersion,
        @JsonProperty("compare_version") int compareVersion,
        @JsonProperty("version_id") int versionId
) {
}
