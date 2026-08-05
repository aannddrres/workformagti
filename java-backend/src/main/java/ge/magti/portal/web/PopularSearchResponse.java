package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors schemas.PopularSearchResponse (schemas.py:659-662). */
public record PopularSearchResponse(@JsonProperty("search_term") String searchTerm, long count) {
}
