package ge.magti.portal.export;

/**
 * Thrown by {@link PdfExportBuilder#build} when {@link GeorgianPdfFont}
 * can't find any candidate TTF. Mirrors the HTTP 503 routers/exports.py's
 * {@code _build_table_pdf} raises (routers/exports.py:214-219) -- but
 * unlike Python, every PDF build in this port runs inside the async job
 * worker ({@link ExportSizeGuard} covers the only synchronous-request
 * failure mode), so this always surfaces as the job's status flipping to
 * {@code failed}, never a request-time 503.
 */
public class PdfFontUnavailableException extends RuntimeException {

    public PdfFontUnavailableException() {
        super("No Unicode/Georgian-capable PDF font found (checked DejaVuSans/Sylfaen candidate paths)");
    }
}
