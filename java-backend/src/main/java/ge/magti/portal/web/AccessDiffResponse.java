package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/** Read-only evidence of who would change at the Phase 4/5 cutover. */
public record AccessDiffResponse(
        @JsonProperty("generated_at") OffsetDateTime generatedAt,
        AccessDiffTotalsResponse totals,
        List<AccessDiffRowResponse> rows
) {
}
