package ge.magti.portal.history;

/**
 * Heap/response budget for backward-compatible full revision-list endpoints.
 *
 * <p>First-party clients use CLOB-free summary lists and one-row detail
 * reads. Legacy clients may still request the established full array, so its
 * aggregate content is checked in Oracle before any CLOB entity hydration.
 */
public final class HistoryPayloadGuard {

    public static final long MAX_FULL_RESPONSE_CHARACTERS = 2_000_000L;

    private HistoryPayloadGuard() {
    }

    public static void enforceFullResponseCharacters(long characters) {
        if (characters > MAX_FULL_RESPONSE_CHARACTERS) {
            throw new HistoryPayloadExceededException();
        }
    }

    public static class HistoryPayloadExceededException extends RuntimeException {
        public HistoryPayloadExceededException() {
            super("History content exceeds the bounded full-response budget");
        }
    }
}
