-- Mirrors models.py's News (models.py:58-79).
CREATE TABLE news (
    id                        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title                     VARCHAR2(500 CHAR) NOT NULL,
    content                   CLOB NOT NULL,
    target_department         VARCHAR2(200 CHAR) DEFAULT 'All',
    created_at                TIMESTAMP(6),
    attachment_url            VARCHAR2(1000 CHAR),
    version                   NUMBER DEFAULT 1,
    visible_to_tech_info      NUMBER(1) DEFAULT 1,
    visible_to_service_center NUMBER(1) DEFAULT 0,
    expires_at                TIMESTAMP(6),
    is_draft                  NUMBER(1) DEFAULT 1,
    author_id                 NUMBER,
    CONSTRAINT fk_news_author FOREIGN KEY (author_id) REFERENCES users (id)
);

CREATE INDEX ix_news_title ON news (title);
CREATE INDEX ix_news_target_department ON news (target_department);
CREATE INDEX ix_news_author_id ON news (author_id);
