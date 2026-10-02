package ge.magti.portal.compliance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Critical" at its edges. Mutation testing (2026-10-02) found that nothing
 * pinned the boundary: "below 30 %" and "30 % or below" passed the same tests.
 */
class ReadingProgressTest {

    @Test
    void thirtyPercentIsNotCriticalAndTwentyNineIs() {
        assertFalse(new ReadingProgress(10, 3, 30).critical());
        assertTrue(new ReadingProgress(100, 29, 29).critical());
    }

    @Test
    void anythingOverdueIsCriticalWhateverThePercentage() {
        assertTrue(new ReadingProgress(10, 9, 90, 1).critical());
    }

    @Test
    void someoneWhoOwesNothingIsNeverCritical() {
        assertFalse(new ReadingProgress(0, 0, 0).critical());
    }
}
