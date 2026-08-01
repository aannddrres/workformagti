-- Mirrors models.py's AuditLog (models.py:336-376). prev_hash/row_hash/
-- ip_address/user_agent stay NULL until Phase 1a's hash-chain trigger is
-- actually built (not yet -- see docs/JAVA_ORACLE_ANGULAR_MIGRATION.md 1a).
CREATE TABLE audit_logs (
    id                  NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    admin_id            NUMBER NOT NULL,
    action              VARCHAR2(50 CHAR) NOT NULL,
    item_type           VARCHAR2(30 CHAR) NOT NULL,
    item_id             NUMBER NOT NULL,
    timestamp           TIMESTAMP(6),
    category            VARCHAR2(20 CHAR),
    details             CLOB,
    admin_name_snapshot VARCHAR2(255 CHAR),
    admin_email_snapshot VARCHAR2(255 CHAR),
    item_name_snapshot  VARCHAR2(500 CHAR),
    prev_hash           VARCHAR2(64 CHAR),
    row_hash            VARCHAR2(64 CHAR),
    ip_address          VARCHAR2(45 CHAR),
    user_agent          VARCHAR2(500 CHAR),
    CONSTRAINT fk_audit_logs_admin FOREIGN KEY (admin_id) REFERENCES users (id)
);

CREATE INDEX ix_audit_logs_admin_id ON audit_logs (admin_id);
CREATE INDEX ix_audit_logs_timestamp ON audit_logs (timestamp);
CREATE INDEX ix_audit_logs_category ON audit_logs (category);
-- Composite indexes match models.py's names exactly (models.py:341-345).
CREATE INDEX ix_audit_logs_category_timestamp ON audit_logs (category, timestamp DESC);
CREATE INDEX ix_audit_logs_admin_timestamp ON audit_logs (admin_id, timestamp DESC);
CREATE INDEX ix_audit_logs_action_timestamp ON audit_logs (action, timestamp DESC);
