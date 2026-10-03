package ge.magti.portal.quiz;

import java.util.List;

/**
 * The quiz-attempt result shape, minus
 * {@code attempt_number} -- that field depends on counting prior attempts
 * in storage, which {@link QuizGrader}
 * deliberately doesn't do. Whoever wires persistence adds attempt_number
 * alongside this result, not into it.
 */
public record QuizGradeResult(boolean passed, int score, int totalQuestions, List<Long> wrongQuestionIds) {
}
