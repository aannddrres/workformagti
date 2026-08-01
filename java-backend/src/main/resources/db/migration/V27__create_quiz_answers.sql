-- Mirrors models.py's QuizAnswer (models.py:556-564).
CREATE TABLE quiz_answers (
    id          NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question_id NUMBER NOT NULL,
    answer_text VARCHAR2(1000 CHAR) NOT NULL,
    is_correct  NUMBER(1) DEFAULT 0 NOT NULL,
    position    NUMBER DEFAULT 0 NOT NULL,
    CONSTRAINT fk_quiz_answers_question FOREIGN KEY (question_id) REFERENCES quiz_questions (id) ON DELETE CASCADE
);

CREATE INDEX ix_quiz_answers_question_id ON quiz_answers (question_id);
