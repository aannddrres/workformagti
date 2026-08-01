-- Mirrors models.py's ArticleHistory (models.py:458-483). version_id is
-- nullable -- Oracle (like Postgres) treats NULL as distinct from any other
-- NULL in a UNIQUE constraint, so multiple NULL-version_id rows per article
-- are allowed, matching the source column's own documented intent.
CREATE TABLE article_history (
    id         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    article_id NUMBER NOT NULL,
    title      VARCHAR2(500 CHAR) NOT NULL,
    content    CLOB NOT NULL,
    updated_at TIMESTAMP(6),
    updated_by NUMBER NOT NULL,
    version_id NUMBER,
    CONSTRAINT fk_article_history_article FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE CASCADE,
    CONSTRAINT fk_article_history_user FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT ux_article_history_article_version UNIQUE (article_id, version_id)
);

CREATE INDEX ix_article_history_article_id ON article_history (article_id);
CREATE INDEX ix_article_history_version_id ON article_history (version_id);
