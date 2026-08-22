package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.BroadcastPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;

import java.time.OffsetDateTime;

/** Fixed-audience request: targeting fields deliberately do not exist. */
public record BroadcastRequest(
        @NotBlank @Size(max = 2000) String message,
        @NotNull BroadcastPriority priority,
        @NotNull @JsonProperty("ends_at") OffsetDateTime endsAt,
        @JsonProperty("target_department") String forbiddenTargetDepartment,
        @JsonProperty("target_role") String forbiddenTargetRole
) {
    /** Rejects legacy targeting instead of silently pretending it was applied. */
    @AssertTrue
    public boolean isCompanyWideAudience() {
        return forbiddenTargetDepartment == null && forbiddenTargetRole == null;
    }
}
