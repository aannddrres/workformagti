-- Mirrors models.py's NewsHistory (models.py:83-93).
CREATE TABLE news_history (
    id             NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    news_id        NUMBER NOT NULL,
    title          VARCHAR2(500 CHAR) NOT NULL,
    content        CLOB NOT NULL,
    attachment_url VARCHAR2(1000 CHAR),
    updated_at     TIMESTAMP(6),
    updated_by     NUMBER NOT NULL,
    CONSTRAINT fk_news_history_news FOREIGN KEY (news_id) REFERENCES news (id),
    CONSTRAINT fk_news_history_user FOREIGN KEY (updated_by) REFERENCES users (id)
);

CREATE INDEX ix_news_history_news_id ON news_history (news_id);
