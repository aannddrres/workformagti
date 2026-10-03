package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ArticleReadReceiptResponse(
        @JsonProperty("article_id") Long articleId,
        @JsonProperty("article_title") String articleTitle,
        @JsonProperty("current_version") int currentVersion,
        @JsonProperty("eligible_count") int eligibleCount,
        @JsonProperty("read_count") int readCount,
        @JsonProperty("unread_count") int unreadCount,
        @JsonProperty("late_read_count") int lateReadCount,
        List<ArticleReadReceiptRowResponse> receipts
) {
}
