package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors get_export_status's {@code {"job_id": ..., "status": ...}} response (routers/exports.py:429). */
public record ExportStatusResponse(@JsonProperty("job_id") String jobId, String status) {
}
