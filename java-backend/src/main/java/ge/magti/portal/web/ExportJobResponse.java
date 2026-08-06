package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors _enqueue_export's {@code {"job_id": job_id}} response (routers/exports.py:403). */
public record ExportJobResponse(@JsonProperty("job_id") String jobId) {
}
