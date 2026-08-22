package ge.magti.portal.web;

import java.util.List;

public record ReminderPageResponse(
        List<ReminderResponse> items,
        int page,
        int size,
        @com.fasterxml.jackson.annotation.JsonProperty("total_elements") long totalElements,
        @com.fasterxml.jackson.annotation.JsonProperty("total_pages") int totalPages) {
}
