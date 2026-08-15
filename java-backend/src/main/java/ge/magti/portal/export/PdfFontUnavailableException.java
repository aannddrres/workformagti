package ge.magti.portal.export;

/**
 * Thrown by {@link PdfExportBuilder#build} when {@link GeorgianPdfFont}
 * can't find any candidate TTF. Mirrors the HTTP 503 routers/exports.py's
 * {@code _build_table_pdf} raises (routers/exports.py:214-219) -- but
 * unlike Python, every PDF build in this port runs inside the async job
 * worker ({@link ExportSizeGuard} covers the only synchronous-request
 * failure mode), so this always surfaces as the job's status flipping to
 * {@code failed}, never a request-time 503.
 *
 * <p>Since PR-02 this should be unreachable in a correctly built artifact:
 * the font is packaged inside the jar rather than expected from the host.
 * {@code GeorgianPdfFontTest} fails the build if the resource goes missing,
 * so this exception now indicates a broken build, not a bare base image.
 */
public class PdfFontUnavailableException extends RuntimeException {

    public PdfFontUnavailableException() {
        super("No Unicode/Georgian-capable PDF font found. Since PR-02 the font is bundled at "
                + GeorgianPdfFont.CLASSPATH_FONT + ", so reaching this means the jar was built without "
                + "src/main/resources/fonts/ -- check the build, not the host's installed fonts.");
    }
}
