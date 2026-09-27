-- A lease makes an interrupted worker distinguishable from a live job on
-- another backend replica. Existing processing rows have NULL lease and are
-- handled as legacy jobs once their original expiry passes.
ALTER TABLE export_jobs ADD (
    worker_instance_id VARCHAR2(36 CHAR),
    lease_until TIMESTAMP(6) WITH TIME ZONE
);

CREATE INDEX ix_export_jobs_processing_lease ON export_jobs (status, lease_until);
