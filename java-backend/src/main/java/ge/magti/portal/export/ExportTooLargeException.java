package ge.magti.portal.export;

/**
 * Thrown by {@link ExportSizeGuard#checkSize(int)} when a requested export
 * exceeds {@link ExportSizeGuard#MAX_ROWS}. Mirrors the HTTP 413 raised by
 * routers/exports.py's {@code _guard_export_size} (routers/exports.py:41-49)
 * -- deliberately not itself HTTP-aware (no status code baked in here),
 * since no web layer is wired up in this port yet. Whichever controller
 * eventually calls this maps it to a 413 response with the Georgian message
 * routers/exports.py:45-48 uses today.
 */
public class ExportTooLargeException extends RuntimeException {

    private final int rowCount;
    private final int maxRows;

    public ExportTooLargeException(int rowCount, int maxRows) {
        super("Export too large: " + rowCount + " rows exceeds limit of " + maxRows);
        this.rowCount = rowCount;
        this.maxRows = maxRows;
    }

    public int getRowCount() {
        return rowCount;
    }

    public int getMaxRows() {
        return maxRows;
    }
}
