package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Mirrors get_notifications_summary's combined bell-icon payload (routers/platform.py:125-197). */
public record NotificationsSummaryResponse(
        @JsonProperty("unread_readings") List<UnreadReadingSummaryItem> unreadReadings,
        @JsonProperty("recent_news") List<RecentNewsSummaryItem> recentNews,
        @JsonProperty("unread_reminders_count") int unreadRemindersCount
) {
}
