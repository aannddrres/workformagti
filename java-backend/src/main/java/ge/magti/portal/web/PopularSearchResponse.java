package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PopularSearchResponse(@JsonProperty("search_term") String searchTerm, long count) {
}
