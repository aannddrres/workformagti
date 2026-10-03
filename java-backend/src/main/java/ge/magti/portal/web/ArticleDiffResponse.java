package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ArticleDiffResponse(
        String html,
        int added,
        int removed,
        @JsonProperty("base_version") int baseVersion,
        @JsonProperty("compare_version") int compareVersion,
        @JsonProperty("version_id") int versionId
) {
}
