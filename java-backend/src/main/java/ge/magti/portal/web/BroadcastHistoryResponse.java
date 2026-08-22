package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record BroadcastHistoryResponse(
        List<BroadcastResponse> items,
        int page,
        int size,
        @JsonProperty("total_items") long totalItems,
        @JsonProperty("total_pages") int totalPages
) {
}
