-- Mirrors models.py's SearchLog (models.py:589-600).
CREATE TABLE search_logs (
    id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       NUMBER NOT NULL,
    search_term   VARCHAR2(500 CHAR) NOT NULL,
    timestamp     TIMESTAMP(6),
    has_results   NUMBER(1) DEFAULT 1,
    results_found NUMBER,
    CONSTRAINT fk_search_logs_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX ix_search_logs_user_id ON search_logs (user_id);
CREATE INDEX ix_search_logs_term ON search_logs (search_term);
