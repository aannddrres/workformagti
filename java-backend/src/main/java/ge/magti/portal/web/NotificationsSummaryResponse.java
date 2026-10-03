package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** The combined bell-icon notifications payload. */
public record NotificationsSummaryResponse(
        @JsonProperty("unread_readings") List<UnreadReadingSummaryItem> unreadReadings,
        @JsonProperty("recent_news") List<RecentNewsSummaryItem> recentNews,
        @JsonProperty("unread_reminders_count") int unreadRemindersCount
) {
}
