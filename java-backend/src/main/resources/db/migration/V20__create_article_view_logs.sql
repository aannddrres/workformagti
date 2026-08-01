-- Mirrors models.py's ArticleViewLog (models.py:512-541). No unique
-- constraint on purpose: repeat views are informative, every open counts.
CREATE TABLE article_view_logs (
    id                            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    article_id                    NUMBER,
    article_title_snapshot        VARCHAR2(500 CHAR) NOT NULL,
    article_version               NUMBER NOT NULL,
    operator_id                   NUMBER,
    operator_name_snapshot        VARCHAR2(200 CHAR) NOT NULL,
    operator_email_snapshot       VARCHAR2(255 CHAR) NOT NULL,
    operator_department_snapshot  VARCHAR2(200 CHAR),
    viewed_at                     TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_view_logs_article FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE SET NULL,
    CONSTRAINT fk_view_logs_operator FOREIGN KEY (operator_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX ix_article_view_logs_article_viewed ON article_view_logs (article_id, viewed_at);
CREATE INDEX ix_article_view_logs_operator_viewed ON article_view_logs (operator_id, viewed_at);
CREATE INDEX ix_article_view_logs_article_version ON article_view_logs (article_id, article_version);
CREATE INDEX ix_article_view_logs_retention_date ON article_view_logs (viewed_at);
