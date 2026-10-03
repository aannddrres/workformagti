package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The export-status {@code {"job_id": ..., "status": ...}} response. */
public record ExportStatusResponse(@JsonProperty("job_id") String jobId, String status) {
}
