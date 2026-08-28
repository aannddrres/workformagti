package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public record VideoCommandRequest(
        @Valid @NotNull VideoInstructionRequest video,
        boolean mandatory,
        @JsonProperty("due_date") OffsetDateTime dueDate,
        @JsonProperty("target_department") String targetDepartment) {
}
