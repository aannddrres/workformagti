package ge.magti.portal.quiz;

import java.util.List;

/**
 * Mirrors the shape routers/articles.py's quiz-attempt endpoint returns
 * (schemas.QuizAttemptResult, schemas.py:350-355), minus
 * {@code attempt_number} -- that field depends on counting prior attempts
 * in storage (routers/articles.py:838-843), which {@link QuizGrader}
 * deliberately doesn't do. Whoever wires persistence adds attempt_number
 * alongside this result, not into it.
 */
public record QuizGradeResult(boolean passed, int score, int totalQuestions, List<Long> wrongQuestionIds) {
}
