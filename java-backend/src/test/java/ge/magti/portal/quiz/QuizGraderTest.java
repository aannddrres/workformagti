package ge.magti.portal.quiz;

import ge.magti.portal.domain.QuizAnswer;
import ge.magti.portal.domain.QuizQuestion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuizGraderTest {

    private static QuizAnswer answer(long id, boolean correct) {
        QuizAnswer answer = new QuizAnswer();
        answer.setId(id);
        answer.setCorrect(correct);
        return answer;
    }

    private static QuizQuestion question(long id, QuizAnswer... answers) {
        QuizQuestion question = new QuizQuestion();
        question.setId(id);
        question.setAnswers(List.of(answers));
        return question;
    }

    @Test
    void allCorrectAnswersPasses() {
        List<QuizQuestion> questions = List.of(
                question(1L, answer(10L, true), answer(11L, false)),
                question(2L, answer(20L, false), answer(21L, true)));

        QuizGradeResult result = QuizGrader.grade(questions, Map.of(1L, 10L, 2L, 21L));

        assertTrue(result.passed());
        assertEquals(2, result.score());
        assertEquals(2, result.totalQuestions());
        assertTrue(result.wrongQuestionIds().isEmpty());
    }

    @Test
    void oneWrongAnswerFailsAndIsListed() {
        List<QuizQuestion> questions = List.of(
                question(1L, answer(10L, true), answer(11L, false)),
                question(2L, answer(20L, false), answer(21L, true)));

        QuizGradeResult result = QuizGrader.grade(questions, Map.of(1L, 10L, 2L, 20L));

        assertFalse(result.passed());
        assertEquals(1, result.score());
        assertEquals(List.of(2L), result.wrongQuestionIds());
    }

    @Test
    void missingAnswerForAQuestionCountsAsWrongNotAnError() {
        List<QuizQuestion> questions = List.of(question(1L, answer(10L, true), answer(11L, false)));

        QuizGradeResult result = QuizGrader.grade(questions, Map.of());

        assertFalse(result.passed());
        assertEquals(List.of(1L), result.wrongQuestionIds());
    }

    @Test
    void questionWithNoCorrectAnswerConfiguredCountsAsWrong() {
        List<QuizQuestion> questions = List.of(question(1L, answer(10L, false), answer(11L, false)));

        QuizGradeResult result = QuizGrader.grade(questions, Map.of(1L, 10L));

        assertFalse(result.passed());
        assertEquals(List.of(1L), result.wrongQuestionIds());
    }
}
