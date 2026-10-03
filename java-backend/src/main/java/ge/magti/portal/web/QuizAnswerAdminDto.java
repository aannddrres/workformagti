package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.QuizAnswer;

/** Includes is_correct. */
public record QuizAnswerAdminDto(
        Long id,
        @JsonProperty("answer_text") String answerText,
        @JsonProperty("is_correct") boolean isCorrect,
        int position
) {
    public static QuizAnswerAdminDto from(QuizAnswer answer) {
        return new QuizAnswerAdminDto(answer.getId(), answer.getAnswerText(), answer.isCorrect(), answer.getPosition());
    }
}
