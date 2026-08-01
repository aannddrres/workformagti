package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.QuizQuestion;

import java.util.List;

/** Mirrors schemas.py's QuizQuestionPublic -- no is_correct anywhere. */
public record QuizQuestionPublicDto(Long id, @JsonProperty("question_text") String questionText, List<QuizAnswerPublicDto> answers) {
    public static QuizQuestionPublicDto from(QuizQuestion question) {
        List<QuizAnswerPublicDto> answers = question.getAnswers().stream()
                .sorted(java.util.Comparator.comparingInt(a -> a.getPosition()))
                .map(QuizAnswerPublicDto::from)
                .toList();
        return new QuizQuestionPublicDto(question.getId(), question.getQuestionText(), answers);
    }
}
