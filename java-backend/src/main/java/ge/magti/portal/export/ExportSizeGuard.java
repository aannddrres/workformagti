package ge.magti.portal.export;

/**
 * Mirrors routers/exports.py's {@code _guard_export_size}
 * (routers/exports.py:38-49): a pathologically large export must be
 * rejected outright rather than silently truncated, since a partial
 * compliance report is more dangerous than a clear "narrow your filter"
 * error. Compliance exports are bounded by user count (~600 today) and sit
 * far below this ceiling.
 */
public final class ExportSizeGuard {

    public static final int MAX_ROWS = 20000;

    private ExportSizeGuard() {
    }

    public static void checkSize(int rowCount) {
        if (rowCount > MAX_ROWS) {
            throw new ExportTooLargeException(rowCount, MAX_ROWS);
        }
    }
}
