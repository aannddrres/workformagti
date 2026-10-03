package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The {@code {"job_id": job_id}} response of an enqueued export. */
public record ExportJobResponse(@JsonProperty("job_id") String jobId) {
}
