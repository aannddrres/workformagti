-- Mirrors models.py's KnowledgeFeedback (models.py:614-628).
CREATE TABLE knowledge_feedback (
    id          NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     NUMBER NOT NULL,
    article_id  NUMBER NOT NULL,
    message     CLOB NOT NULL,
    status      VARCHAR2(20 CHAR) DEFAULT 'open',
    created_at  TIMESTAMP(6),
    resolved_at TIMESTAMP(6),
    resolved_by NUMBER,
    CONSTRAINT fk_knowledge_feedback_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_knowledge_feedback_article FOREIGN KEY (article_id) REFERENCES articles (id),
    CONSTRAINT fk_knowledge_feedback_resolver FOREIGN KEY (resolved_by) REFERENCES users (id)
);

CREATE INDEX ix_knowledge_feedback_user_id ON knowledge_feedback (user_id);
CREATE INDEX ix_knowledge_feedback_article_id ON knowledge_feedback (article_id);
CREATE INDEX ix_knowledge_feedback_status ON knowledge_feedback (status);
