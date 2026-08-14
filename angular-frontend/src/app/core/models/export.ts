/** Mirrors web.ExportJobResponse -- returned by the 3 async submit endpoints. */
export interface ExportJobResponse {
  job_id: string;
}

export type ExportJobStatus = 'processing' | 'completed' | 'failed';

/** Mirrors web.ExportStatusResponse -- GET /api/export/status/{jobId}. */
export interface ExportStatus {
  job_id: string;
  status: ExportJobStatus;
}
