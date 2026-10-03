package ge.magti.portal.web;

import java.util.List;

/**
 * Used as both the PUT request body
 * and the GET/PUT response. Deliberately has no bean-validation annotations: the "at least
 * one question" / "at least 2 answers" / "exactly one correct answer"
 * checks are explicit imperative code in QuizController (422s with specific
 * Georgian text), not schema-level constraints, rather than a generic 400
 * shape.
 */
public record QuizAdminUpdate(List<QuizQuestionAdminDto> questions) {
}
