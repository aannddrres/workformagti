package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record QuizAttemptResultResponse(
        boolean passed,
        int score,
        @JsonProperty("total_questions") int totalQuestions,
        @JsonProperty("wrong_question_ids") List<Long> wrongQuestionIds,
        @JsonProperty("attempt_number") int attemptNumber
) {
}
