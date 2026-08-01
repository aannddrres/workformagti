-- Mirrors models.py's ReadStatus (models.py:217-237).
CREATE TABLE read_statuses (
    id                            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id                       NUMBER NOT NULL,
    required_reading_id           NUMBER NOT NULL,
    status                        VARCHAR2(20 CHAR) DEFAULT 'unread',
    read_at                       TIMESTAMP(6),
    operator_department_snapshot  VARCHAR2(200 CHAR),
    CONSTRAINT fk_read_statuses_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_read_statuses_reading FOREIGN KEY (required_reading_id) REFERENCES required_readings (id),
    CONSTRAINT uq_read_status_user_reading UNIQUE (user_id, required_reading_id)
);

CREATE INDEX ix_read_status_reading_status ON read_statuses (required_reading_id, status);
