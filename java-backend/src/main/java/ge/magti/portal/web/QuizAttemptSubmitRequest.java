package ge.magti.portal.web;

import java.util.Map;

/** Mirrors schemas.py's QuizAttemptSubmit: {question_id: chosen_answer_id}. */
public record QuizAttemptSubmitRequest(Map<Long, Long> answers) {
    public Map<Long, Long> answersOrEmpty() {
        return answers == null ? Map.of() : answers;
    }
}
