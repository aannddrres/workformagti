package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Mirrors schemas.py's ArticleReadReceiptResponse. */
public record ArticleReadReceiptResponse(
        @JsonProperty("article_id") Long articleId,
        @JsonProperty("article_title") String articleTitle,
        @JsonProperty("current_version") int currentVersion,
        List<ArticleReadReceiptRowResponse> receipts
) {
}
