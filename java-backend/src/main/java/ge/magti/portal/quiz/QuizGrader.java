package ge.magti.portal.quiz;

import ge.magti.portal.domain.QuizAnswer;
import ge.magti.portal.domain.QuizQuestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ports the one part of routers/articles.py's
 * {@code submit_article_quiz_attempt} (:825-836) that needs no database:
 * scoring already-loaded questions against submitted answers. Grading a
 * quiz requires 100% -- there is no partial-credit threshold, matching
 * {@code Article.quiz_enabled}'s doc comment (models.py:147-150) and
 * routers/articles.py:836's {@code passed = score == total} exactly.
 *
 * <p>Deliberately NOT ported here (both require storage,
 * routers/articles.py:838-854): computing {@code attemptNumber} from prior
 * attempts, and persisting the resulting
 * {@link ge.magti.portal.domain.QuizAttempt} row. Those are repository-layer
 * work for whenever the DB question is revisited.
 */
public final class QuizGrader {

    private QuizGrader() {
    }

    /**
     * @param questions        the article's quiz questions, each with its answers loaded
     * @param submittedAnswers {questionId: chosenAnswerId}, mirroring
     *                         schemas.QuizAttemptSubmit (schemas.py:345-347) -- a
     *                         question missing from this map is graded wrong, exactly
     *                         like Python's {@code dict.get} returning {@code None}
     *                         (routers/articles.py:829-830), not an error.
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
