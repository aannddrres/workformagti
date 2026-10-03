package ge.magti.portal.quiz;

import ge.magti.portal.domain.QuizAnswer;
import ge.magti.portal.domain.QuizQuestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The one part of quiz submission that needs no database:
 * scoring already-loaded questions against submitted answers. Grading a
 * quiz requires 100% -- there is no partial-credit threshold
 * ({@code passed = score == total}).
 *
 * <p>Deliberately NOT done here (both require storage): computing
 * {@code attemptNumber} from prior
 * attempts, and persisting the resulting
 * {@link ge.magti.portal.domain.QuizAttempt} row. Those are repository-layer
 * work for whenever the DB question is revisited.
 */
public final class QuizGrader {

    private QuizGrader() {
    }

    /**
     * @param questions        the article's quiz questions, each with its answers loaded
     * @param submittedAnswers {questionId: chosenAnswerId} -- a
     *                         question missing from this map is graded wrong,
     *                         not an error.
     */
    public static QuizGradeResult grade(List<QuizQuestion> questions, Map<Long, Long> submittedAnswers) {
        List<Long> wrongQuestionIds = new ArrayList<>();
        int score = 0;

        for (QuizQuestion question : questions) {
            Long correctAnswerId = question.getAnswers().stream()
                    .filter(QuizAnswer::isCorrect)
                    .map(QuizAnswer::getId)
                    .findFirst()
                    .orElse(null);
            Long chosenAnswerId = submittedAnswers.get(question.getId());

            if (correctAnswerId != null && Objects.equals(chosenAnswerId, correctAnswerId)) {
                score++;
            } else {
                wrongQuestionIds.add(question.getId());
            }
        }

        int total = questions.size();
        return new QuizGradeResult(score == total, score, total, wrongQuestionIds);
    }
}
