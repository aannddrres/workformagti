-- PR-03 / BL-09: move uploaded attachments and generated export files off the
-- pod's own filesystem and into Oracle, so they survive a restart and are
-- readable from every replica.
--
-- Why the database rather than a ReadWriteMany volume or object storage: this
-- repository has no Kubernetes manifests at all and docs/QUESTIONS_FOR_IT.md
-- has open questions about the cluster, so any volume- or S3-based answer
-- would be a promise the repo cannot keep. Oracle is already a hard
-- dependency, already backed up, and already the thing the export_jobs row
-- lives in. See the class javadoc on FileStorageService for the full
-- comparison and for what this costs.
CREATE TABLE stored_files (
    filename     VARCHAR2(100 CHAR) PRIMARY KEY,
    content_type VARCHAR2(150 CHAR) NOT NULL,
    byte_size    NUMBER NOT NULL,
    uploaded_by  NUMBER,
    created_at   TIMESTAMP(6),
    -- Deliberately nullable. A zero-byte upload passes the MIME allowlist,
    -- and whether Oracle stores an empty LOB as a locator or as NULL is not
    -- worth betting a 500 on for a case byte_size already records.
    content      BLOB,
    CONSTRAINT fk_stored_files_user FOREIGN KEY (uploaded_by) REFERENCES users (id) ON DELETE SET NULL
);

-- export_jobs.path stays: rows written before this migration still point at a
-- pod-local file, and ExportJobCleanupScheduler still deletes those. New jobs
-- write content/filename instead and leave path NULL.
ALTER TABLE export_jobs ADD (
    content  BLOB,
    filename VARCHAR2(200 CHAR)
);
