-- A6 (release audit): an attempt number belongs to one attempt per person,
-- article and version. QuizController has numbered attempts under the
-- submitter's users-row lock since the release audit; before that, two
-- submissions in flight could both be "attempt 1"
-- (ConcurrentQuizAttemptIntegrationTest). This makes the database refuse such
-- a pair too, whichever code path writes it.
--
-- Keyed on article_id_snapshot, not article_id. V42 made article_id
-- ON DELETE SET NULL, so a purge nulls it on every attempt of that article,
-- and two purged articles' first attempts would then collide and fail the
-- purge. The snapshot is written once and never changes.
--
-- ENABLE NOVALIDATE, over a deliberately non-unique index. Duplicates written
-- before the lock are compliance evidence and stay as they are, neither
-- renumbered nor deleted; a unique index would refuse to build over them.
-- New rows are checked (QuizAttemptNumberConstraintIntegrationTest).
CREATE INDEX ix_quiz_attempts_attempt_no
    ON quiz_attempts (user_id, article_id_snapshot, article_version, attempt_number);

ALTER TABLE quiz_attempts ADD CONSTRAINT uq_quiz_attempts_attempt_no
    UNIQUE (user_id, article_id_snapshot, article_version, attempt_number)
    USING INDEX ix_quiz_attempts_attempt_no ENABLE NOVALIDATE;
