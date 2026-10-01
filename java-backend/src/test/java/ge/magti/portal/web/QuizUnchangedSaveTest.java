package ge.magti.portal.web;

import ge.magti.portal.domain.QuizAnswer;
import ge.magti.portal.domain.QuizQuestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The article editor sends its quiz with every save. Replacing an identical
 * quiz gave every question new ids, and an operator who had it open failed
 * with 0 points after a tag edit (simulation, 2026-10-01). An unchanged quiz
 * is now left alone; any real change still replaces it.
 */
class QuizUnchangedSaveTest {

    @Test
    void theSameQuizSentBackIsRecognisedAsUnchanged() {
        assertTrue(QuizController.sameQuiz(List.of(stored("Q1", "a", "b")), List.of(sent("Q1", "a", "b", 0))));
    }

    @Test
    void anyRealChangeIsAChange() {
        List<QuizQuestion> current = List.of(stored("Q1", "a", "b"));
        assertFalse(QuizController.sameQuiz(current, List.of(sent("Q1 edited", "a", "b", 0))), "question text");
        assertFalse(QuizController.sameQuiz(current, List.of(sent("Q1", "a", "c", 0))), "answer text");
        assertFalse(QuizController.sameQuiz(current, List.of(sent("Q1", "a", "b", 1))), "correct answer");
        assertFalse(QuizController.sameQuiz(current, List.of(sent("Q1", "a", "b", 0), sent("Q2", "x", "y", 0))),
                "an added question");
    }

    private static QuizQuestion stored(String text, String correct, String wrong) {
        QuizQuestion question = new QuizQuestion();
        question.setQuestionText(text);
        QuizAnswer first = new QuizAnswer();
        first.setAnswerText(correct);
        first.setCorrect(true);
        first.setPosition(0);
        QuizAnswer second = new QuizAnswer();
        second.setAnswerText(wrong);
        second.setPosition(1);
        // Stored out of order on purpose: position decides, not list order.
        question.setAnswers(List.of(second, first));
        return question;
    }

    private static QuizQuestionAdminDto sent(String text, String first, String second, int correctIndex) {
        return new QuizQuestionAdminDto(null, text, 0, List.of(
                new QuizAnswerAdminDto(null, first, correctIndex == 0, 0),
                new QuizAnswerAdminDto(null, second, correctIndex == 1, 1)));
    }
}
