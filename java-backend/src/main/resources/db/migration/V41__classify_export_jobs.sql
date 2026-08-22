-- R4: classify high-sensitivity SYSTEM_ADMIN data exports so the shared
-- status/download endpoints can enforce strict owner-only access for them.
ALTER TABLE export_jobs ADD (export_family VARCHAR2(50 CHAR));

CREATE INDEX ix_export_jobs_family_owner
    ON export_jobs (export_family, owner_user_id, expires_at);
