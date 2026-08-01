package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.QuizQuestion;

import java.util.List;

/** Mirrors schemas.py's QuizQuestionAdmin -- each answer includes is_correct. */
public record QuizQuestionAdminDto(
        Long id,
        @JsonProperty("question_text") String questionText,
        int position,
        List<QuizAnswerAdminDto> answers
) {
    public static QuizQuestionAdminDto from(QuizQuestion question) {
        List<QuizAnswerAdminDto> answers = question.getAnswers().stream()
                .sorted(java.util.Comparator.comparingInt(a -> a.getPosition()))
                .map(QuizAnswerAdminDto::from)
                .toList();
        return new QuizQuestionAdminDto(question.getId(), question.getQuestionText(), question.getPosition(), answers);
    }
}
