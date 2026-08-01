package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.QuizAnswer;

/** Mirrors schemas.py's QuizAnswerPublic -- no is_correct anywhere. */
public record QuizAnswerPublicDto(Long id, @JsonProperty("answer_text") String answerText) {
    public static QuizAnswerPublicDto from(QuizAnswer answer) {
        return new QuizAnswerPublicDto(answer.getId(), answer.getAnswerText());
    }
}
