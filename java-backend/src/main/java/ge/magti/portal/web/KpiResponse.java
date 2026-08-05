package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors schemas.KpiResponse (schemas.py:579-584). */
public record KpiResponse(long users, long articles, @JsonProperty("required_readings") long requiredReadings, long videos) {
}
