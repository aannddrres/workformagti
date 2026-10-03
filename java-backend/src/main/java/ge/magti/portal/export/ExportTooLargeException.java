package ge.magti.portal.export;

/**
 * Thrown by {@link ExportSizeGuard#checkSize(int)} when a requested export
 * exceeds {@link ExportSizeGuard#MAX_ROWS}. Deliberately not itself
 * HTTP-aware (no status code baked in here): the controller that calls
 * this maps it to a 413 response with a Georgian message.
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
