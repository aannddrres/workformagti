package ge.magti.portal.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompleteResultGuardTest {

    @Test
    void queryUsesOneSentinelAndAcceptsTheExactCeiling() {
        List<Integer> rows = IntStream.range(0, CompleteResultGuard.MAX_ROWS).boxed().toList();

        assertEquals(CompleteResultGuard.MAX_ROWS + 1, CompleteResultGuard.sentinelPage().getPageSize());
        assertSame(rows, CompleteResultGuard.enforce(rows));
        CompleteResultGuard.enforceSize(rows.size());
    }

    @Test
    void collectionOrExpandedResponseFailsLoudlyOnOverflow() {
        List<Integer> rows = IntStream.range(0, CompleteResultGuard.MAX_ROWS + 1).boxed().toList();

        assertThrows(CompleteResultGuard.CompleteResultCardinalityExceededException.class,
                () -> CompleteResultGuard.enforce(rows));
        assertThrows(CompleteResultGuard.CompleteResultCardinalityExceededException.class,
                () -> CompleteResultGuard.enforceSize(rows.size()));
    }
}
