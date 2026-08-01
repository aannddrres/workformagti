-- Mirrors models.py's QuizAttempt (models.py:567-587).
CREATE TABLE quiz_attempts (
    id               NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    article_id       NUMBER NOT NULL,
    article_version  NUMBER NOT NULL,
    user_id          NUMBER NOT NULL,
    attempt_number   NUMBER NOT NULL,
    score            NUMBER NOT NULL,
    total_questions  NUMBER NOT NULL,
    passed           NUMBER(1) DEFAULT 0,
    created_at       TIMESTAMP(6),
    CONSTRAINT fk_quiz_attempts_article FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE CASCADE,
    CONSTRAINT fk_quiz_attempts_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX ix_quiz_attempts_user_article_version ON quiz_attempts (user_id, article_id, article_version);
CREATE INDEX ix_quiz_attempts_passed ON quiz_attempts (passed);
