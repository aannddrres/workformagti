-- Mirrors models.py's UserNote (models.py:603-611).
CREATE TABLE user_notes (
    id         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    NUMBER NOT NULL,
    article_id NUMBER NOT NULL,
    content    CLOB NOT NULL,
    created_at TIMESTAMP(6),
    updated_at TIMESTAMP(6),
    CONSTRAINT fk_user_notes_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_notes_article FOREIGN KEY (article_id) REFERENCES articles (id)
);

CREATE INDEX ix_user_notes_user_id ON user_notes (user_id);
CREATE INDEX ix_user_notes_article_id ON user_notes (article_id);
