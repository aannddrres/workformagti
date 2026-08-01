-- Mirrors models.py's ArticleReadReceipt (models.py:486-509). FKs are
-- ON DELETE SET NULL (not CASCADE) so a receipt survives the article or
-- operator being deleted -- the snapshot columns keep the historical facts
-- readable.
CREATE TABLE article_read_receipts (
    id                            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    article_id                    NUMBER,
    article_title_snapshot        VARCHAR2(500 CHAR) NOT NULL,
    article_version               NUMBER NOT NULL,
    operator_id                   NUMBER,
    operator_name_snapshot        VARCHAR2(200 CHAR) NOT NULL,
    operator_email_snapshot       VARCHAR2(255 CHAR) NOT NULL,
    operator_department_snapshot  VARCHAR2(200 CHAR),
    read_at                       TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_read_receipts_article FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE SET NULL,
    CONSTRAINT fk_read_receipts_operator FOREIGN KEY (operator_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT uq_article_read_receipt_version_operator UNIQUE (article_id, article_version, operator_id)
);

CREATE INDEX ix_article_read_receipts_article_version ON article_read_receipts (article_id, article_version);
CREATE INDEX ix_article_read_receipts_article_operator ON article_read_receipts (article_id, operator_id);
CREATE INDEX idx_receipts_perf_lookup ON article_read_receipts (article_id, article_version, read_at);
CREATE INDEX idx_receipts_retention_date ON article_read_receipts (read_at);
