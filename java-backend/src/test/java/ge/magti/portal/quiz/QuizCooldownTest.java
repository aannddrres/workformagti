package ge.magti.portal.quiz;

import ge.magti.portal.domain.QuizAttempt;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** PO-55: three failures in a row, then ten minutes. */
class QuizCooldownTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-03T12:00:00+04:00");

    private static QuizAttempt attempt(boolean passed, int minutesAgo) {
        QuizAttempt a = new QuizAttempt();
        a.setPassed(passed);
        a.setCreatedAt(NOW.minusMinutes(minutesAgo));
        return a;
    }

    @Test
    void fewerThanThreeAttemptsNeverWait() {
        assertEquals(0, QuizCooldown.secondsToWait(List.of(), NOW));
        assertEquals(0, QuizCooldown.secondsToWait(List.of(attempt(false, 0), attempt(false, 1)), NOW));
    }

    @Test
    void threeFailuresInARowWaitTenMinutesFromTheLast() {
        List<QuizAttempt> newestFirst = List.of(attempt(false, 1), attempt(false, 2), attempt(false, 3));
        assertEquals(9 * 60, QuizCooldown.secondsToWait(newestFirst, NOW));
    }

    @Test
    void theWaitEndsAfterTenMinutes() {
        List<QuizAttempt> newestFirst = List.of(attempt(false, 10), attempt(false, 11), attempt(false, 12));
        assertEquals(0, QuizCooldown.secondsToWait(newestFirst, NOW));
    }

    @Test
    void aPassAmongTheLastThreeMeansNoWait() {
        List<QuizAttempt> newestFirst = List.of(attempt(false, 1), attempt(true, 2), attempt(false, 3));
        assertEquals(0, QuizCooldown.secondsToWait(newestFirst, NOW));
    }

    @Test
    void aFourthFailureAfterTheWaitStartsTheWaitAgain() {
        // Waited out the ten minutes, failed once more: the newest three are failures again.
        List<QuizAttempt> newestFirst = List.of(attempt(false, 0), attempt(false, 11), attempt(false, 12));
        assertEquals(10 * 60, QuizCooldown.secondsToWait(newestFirst, NOW));
    }
}
