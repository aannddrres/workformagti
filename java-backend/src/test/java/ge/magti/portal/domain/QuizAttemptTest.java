package ge.magti.portal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class QuizAttemptTest {

    @Test
    void newInstanceMatchesModelsPyColumnDefaults() {
        QuizAttempt attempt = new QuizAttempt();

        assertFalse(attempt.isPassed());
    }
}
