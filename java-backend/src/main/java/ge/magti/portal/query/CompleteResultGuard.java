package ge.magti.portal.query;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

/** Shared Oracle sentinel and fail-loud contract for legacy complete-result collections. */
public final class CompleteResultGuard {

    public static final int MAX_ROWS = 1_000;

    private CompleteResultGuard() {
    }

    public static Pageable sentinelPage() {
        return PageRequest.of(0, MAX_ROWS + 1);
    }

    public static <T> List<T> enforce(List<T> rows) {
        enforceSize(rows.size());
        return rows;
    }

    public static void enforceSize(int size) {
        if (size > MAX_ROWS) {
            throw new CompleteResultCardinalityExceededException();
        }
    }

    public static class CompleteResultCardinalityExceededException extends RuntimeException {
        public CompleteResultCardinalityExceededException() {
            super("Complete-result collection exceeds the bounded response");
        }
    }
}
