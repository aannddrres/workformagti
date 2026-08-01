-- Mirrors models.py's ExportJob (models.py:675-685). id is an
-- application-assigned UUID4 string, not a DB-generated identity.
-- expires_at is a raw Unix-epoch-seconds float (routers/exports.py), not a
-- Tbilisi timestamp -- BINARY_DOUBLE maps directly to Java double.
CREATE TABLE export_jobs (
    id         VARCHAR2(36 CHAR) PRIMARY KEY,
    status     VARCHAR2(20 CHAR) DEFAULT 'processing' NOT NULL,
    path       VARCHAR2(1000 CHAR),
    expires_at BINARY_DOUBLE NOT NULL
);
