-- PO-16: fixed, one-way portal reminders. This is deliberately separate
-- from the legacy messages table, whose rows cannot be safely classified or
-- backfilled as reminders after the fact.
CREATE TABLE reminders (
    id                         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recipient_user_id          NUMBER,
    recipient_name_snapshot    VARCHAR2(255 CHAR) NOT NULL,
    required_reading_id        NUMBER,
    reminder_type              VARCHAR2(20 CHAR) NOT NULL,
    content_snapshot           CLOB NOT NULL,
    item_type_snapshot         VARCHAR2(20 CHAR),
    item_id_snapshot           NUMBER,
    item_title_snapshot        VARCHAR2(500 CHAR),
    due_at_snapshot            TIMESTAMP(6),
    triggered_by_user_id       NUMBER,
    triggered_by_name_snapshot VARCHAR2(255 CHAR) NOT NULL,
    created_at                 TIMESTAMP(6) NOT NULL,
    read_at                    TIMESTAMP(6),
    lock_version               NUMBER DEFAULT 0 NOT NULL,
    CONSTRAINT fk_reminder_recipient FOREIGN KEY (recipient_user_id)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_reminder_reading FOREIGN KEY (required_reading_id)
        REFERENCES required_readings (id) ON DELETE SET NULL,
    CONSTRAINT fk_reminder_trigger FOREIGN KEY (triggered_by_user_id)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_reminder_type CHECK (
        reminder_type IN ('ASSIGNMENT', 'DUE_SOON', 'OVERDUE', 'MANUAL')
    ),
    CONSTRAINT ck_reminder_read_at CHECK (read_at IS NULL OR read_at >= created_at),
    CONSTRAINT uq_reminder_once UNIQUE (
        required_reading_id, recipient_user_id, reminder_type
    )
);

CREATE INDEX ix_reminder_recipient ON reminders (recipient_user_id, created_at);
CREATE INDEX ix_reminder_reading ON reminders (required_reading_id);
CREATE INDEX ix_reminder_manual_cd ON reminders (recipient_user_id, reminder_type, created_at);

-- Scheduled reminders are genuine system actors. A nullable actor id plus the
-- immutable "სისტემა" name snapshot is more truthful than attributing the
-- event to the recipient or inventing a login-capable service account.
ALTER TABLE audit_logs MODIFY (admin_id NULL);
