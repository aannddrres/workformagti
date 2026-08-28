package ge.magti.portal.history;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HistoryPayloadGuardTest {

    @Test
    void acceptsTheExactFullResponseCharacterBudget() {
        assertDoesNotThrow(() -> HistoryPayloadGuard.enforceFullResponseCharacters(
                HistoryPayloadGuard.MAX_FULL_RESPONSE_CHARACTERS));
    }

    @Test
    void rejectsTheFirstCharacterAboveTheFullResponseBudget() {
        assertThrows(HistoryPayloadGuard.HistoryPayloadExceededException.class,
                () -> HistoryPayloadGuard.enforceFullResponseCharacters(
                        HistoryPayloadGuard.MAX_FULL_RESPONSE_CHARACTERS + 1));
    }
}
