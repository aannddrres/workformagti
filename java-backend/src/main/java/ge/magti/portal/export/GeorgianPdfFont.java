package ge.magti.portal.export;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Mirrors routers/exports.py's {@code _register_pdf_font}
 * (routers/exports.py:182-203): locates a Unicode/Georgian-capable TTF from
 * a fixed candidate list and caches the result.
 *
 * <p>Unlike ReportLab's {@code pdfmetrics.registerFont} (a process-wide
 * registry -- register once, reuse the font name across every future
 * document), PDFBox 3's {@link PDType0Font} is embedded per-{@link
 * PDDocument}; a loaded font object cannot be reused across documents. So
 * this class caches only the resolved file path (the part that's genuinely
 * expensive to redo -- filesystem probing), and {@link #load(PDDocument)}
 * embeds a fresh {@link PDFont} for whichever document is being built.
 *
 * <p>The Docker/production candidate expects {@code fonts-dejavu-core}
 * installed the same way the Python Dockerfile already does (see root
 * {@code Dockerfile}) -- no Java-side Dockerfile exists yet to wire that
 * into, so this is a note for whenever one is written, not something built
 * here. Confirmed present on this dev machine: {@code sylfaen.ttf} (Windows'
 * native Georgian-capable font), the same fallback Python's own candidate
 * list uses.
 */
public final class GeorgianPdfFont {

    private static final String[] CANDIDATES = {
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans.ttf",
            "C:/Windows/Fonts/dejavusans.ttf",
            "C:/Windows/Fonts/sylfaen.ttf",
    };

    private static volatile Optional<Path> resolved;

    private GeorgianPdfFont() {
    }

    public static Optional<Path> resolvePath() {
        Optional<Path> local = resolved;
        if (local == null) {
            synchronized (GeorgianPdfFont.class) {
                local = resolved;
                if (local == null) {
                    local = findCandidate();
                    resolved = local;
                }
            }
        }
        return local;
    }

    /** Embeds a fresh {@link PDFont} into {@code document}, or empty if no candidate font exists. */
    public static Optional<PDFont> load(PDDocument document) throws IOException {
        Optional<Path> path = resolvePath();
        if (path.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(PDType0Font.load(document, path.get().toFile()));
    }

    private static Optional<Path> findCandidate() {
        for (String candidate : CANDIDATES) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }
}
