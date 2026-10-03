package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.stats.CriticalOperator;

import java.time.OffsetDateTime;
import java.util.List;

public record CriticalOperatorsResponse(
        List<CriticalOperator> operators,
        int total,
        @JsonProperty("generated_at") OffsetDateTime generatedAt) {
}
