package ge.magti.portal.web;

import java.util.List;

/**
 * Mirrors schemas.py's QuizAdminUpdate -- used as both the PUT request body
 * and the GET/PUT response (routers/articles.py reuses one schema for all
 * three). Deliberately has no bean-validation annotations: the "at least
 * one question" / "at least 2 answers" / "exactly one correct answer"
 * checks are explicit imperative code in Python (422s with specific
 * Georgian text), not schema-level constraints -- QuizController replicates
 * them the same way rather than switching to a generic 400 shape.
 */
public record QuizAdminUpdate(List<QuizQuestionAdminDto> questions) {
}
