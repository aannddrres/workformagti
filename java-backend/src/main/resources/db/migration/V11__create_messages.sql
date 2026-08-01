-- Mirrors models.py's Message (models.py:311-333).
CREATE TABLE messages (
    id         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    NUMBER NOT NULL,
    sender_id  NUMBER,
    content    CLOB NOT NULL,
    is_read    NUMBER(1) DEFAULT 0,
    created_at TIMESTAMP(6),
    CONSTRAINT fk_messages_recipient FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_messages_sender FOREIGN KEY (sender_id) REFERENCES users (id)
);

CREATE INDEX ix_messages_user_id ON messages (user_id);
-- Matches models.py's ix_messages_sender_id (models.py:315).
CREATE INDEX ix_messages_sender_id ON messages (sender_id);
