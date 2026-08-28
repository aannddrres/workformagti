CREATE TABLE portal_sessions (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    user_id NUMBER(19) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    last_seen_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6),
    client_ip VARCHAR2(64 CHAR),
    user_agent VARCHAR2(500 CHAR),
    CONSTRAINT fk_portal_sessions_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX ix_portal_sessions_user_active
    ON portal_sessions (user_id, revoked_at, expires_at);

CREATE INDEX ix_portal_sessions_last_seen
    ON portal_sessions (last_seen_at);
