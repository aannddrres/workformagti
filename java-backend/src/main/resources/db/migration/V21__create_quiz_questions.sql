-- Mirrors models.py's QuizQuestion (models.py:544-553).
CREATE TABLE quiz_questions (
    id            NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    article_id    NUMBER NOT NULL,
    question_text CLOB NOT NULL,
    position      NUMBER DEFAULT 0 NOT NULL,
    CONSTRAINT fk_quiz_questions_article FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE CASCADE
);

CREATE INDEX ix_quiz_questions_article_id ON quiz_questions (article_id);
