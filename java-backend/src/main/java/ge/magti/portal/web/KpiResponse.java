package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KpiResponse(long users, long articles, @JsonProperty("required_readings") long requiredReadings, long videos) {
}
