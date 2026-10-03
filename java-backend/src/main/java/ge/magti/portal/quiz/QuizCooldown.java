package ge.magti.portal.quiz;

import ge.magti.portal.domain.QuizAttempt;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * After three failed attempts in a row, the next one waits ten minutes
 * (PO-55, owner 2026-10-03).
 *
 * <p>QA round 5 passed a quiz by trial and error: the result names the
 * questions answered wrongly, and attempts were unlimited and immediate, so
 * a ten-question quiz falls to a few quick guesses (scripts/qa/check_floods.py,
 * E5). The owner kept the feedback -- it is how the quiz teaches -- and
 * slowed the guessing instead. After the wait one more attempt is allowed; if
 * it fails too, the last three are failures again and the wait starts over.
 * A pass anywhere among the last three means no wait. Counted per article
 * version, like attempt numbers: a new version is a new quiz.
 */
public final class QuizCooldown {

    public static final int FAILURES = 3;
    public static final Duration WAIT = Duration.ofMinutes(10);

    private QuizCooldown() {
    }

    /**
     * Seconds until the next attempt is allowed, or 0 when it is allowed now.
     *
     * @param newestFirst the submitter's latest attempts on this version, newest first
     */
    public static long secondsToWait(List<QuizAttempt> newestFirst, OffsetDateTime now) {
        if (newestFirst.size() < FAILURES) {
            return 0;
        }
        List<QuizAttempt> lastThree = newestFirst.subList(0, FAILURES);
        if (lastThree.stream().anyMatch(QuizAttempt::isPassed)) {
            return 0;
        }
        OffsetDateTime newest = lastThree.get(0).getCreatedAt();
        if (newest == null) {
            return 0;
        }
        long left = Duration.between(now, newest.plus(WAIT)).getSeconds();
        return Math.max(0, left);
    }
}
