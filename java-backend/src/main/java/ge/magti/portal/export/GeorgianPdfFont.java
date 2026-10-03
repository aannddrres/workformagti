package ge.magti.portal.export;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Locates a Unicode/Georgian-capable TTF and
 * embeds it into the document being built.
 *
 * <p>Unlike ReportLab's {@code pdfmetrics.registerFont} (a process-wide
 * registry -- register once, reuse the font name across every future
 * document), PDFBox 3's {@link PDType0Font} is embedded per-{@link
 * PDDocument}; a loaded font object cannot be reused across documents. So
 * this class resolves a <i>source</i> once and {@link #load(PDDocument)}
 * embeds a fresh {@link PDFont} for whichever document is being built.
 *
 * <h2>Why the font is bundled (audit PR-02)</h2>
 *
 * This class used to probe four hard-coded filesystem paths only -- two
 * Debian DejaVu locations and two Windows ones. The runtime stage of
 * {@code java-backend/Dockerfile} is {@code eclipse-temurin:21-jre-alpine}
 * and installs no packages at all, so <b>none of the four existed in the
 * shipping image</b>: {@code PdfExportBuilder} threw
 * {@link PdfFontUnavailableException}, {@code ExportJobWorker} flipped the
 * job to "failed", and every user saw "export failed" with no reason, every
 * time, for every PDF.
 *
 * <p>Installing a font in the image would have fixed the symptom, but two
 * things argue against relying on that alone. Alpine ships DejaVu as {@code
 * ttf-dejavu} at {@code /usr/share/fonts/ttf-dejavu/DejaVuSans.ttf}, which
 * matches none of the Debian-shaped candidates -- so the obvious
 * "apk add ttf-dejavu" would have left the bug in place while looking like
 * a fix. And more fundamentally, a base-image change or a switch to
 * distroless would silently break PDF export again, in exactly the same
 * invisible way.
 *
 * <p>So the font travels with the code: {@code src/main/resources/fonts/}
 * is inside the jar, and {@link #CLASSPATH_FONT} is tried first. The
 * filesystem candidates are kept as a fallback -- widened to include
 * Alpine's real path -- so an operator can still override with a system
 * font if they ever need to, but nothing depends on one being present.
 * Licence: Bitstream Vera, which explicitly permits redistribution as part
 * of a larger package; the notice it requires ships alongside the file as
 * {@code fonts/LICENSE-DejaVu.txt}.
 */
public final class GeorgianPdfFont {

    /** Inside the jar. See the class javadoc for why this is not optional. */
    static final String CLASSPATH_FONT = "/fonts/DejaVuSans.ttf";

    /**
     * Fallback only. Kept so a developer's or operator's system font still
     * works, and so an image that deliberately supplies its own font can be
     * used. The Alpine path is first because that is what this project's own
     * runtime image would have if anyone installs {@code ttf-dejavu}; its
     * absence from this list is what made "install the font" a non-fix.
     */
    private static final String[] FILESYSTEM_CANDIDATES = {
            "/usr/share/fonts/ttf-dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans.ttf",
            "C:/Windows/Fonts/dejavusans.ttf",
            "C:/Windows/Fonts/sylfaen.ttf",
    };

    private static volatile Optional<Path> resolvedFile;

    private GeorgianPdfFont() {
    }

    /**
     * Whether a Georgian-capable font can be loaded at all. Always true in a
     * correctly built jar -- {@link #CLASSPATH_FONT} is packaged -- and
     * asserted by {@code GeorgianPdfFontTest} so a build that loses the
     * resource fails in CI rather than at a user's first PDF export.
     */
    public static boolean isAvailable() {
        return classpathFontExists() || resolveFilesystemPath().isPresent();
    }

    /**
     * The filesystem font in use, if the classpath one is absent. Empty when
     * the bundled font is being used (the normal case) -- callers wanting
     * "can we build a PDF at all" want {@link #isAvailable()}.
     */
    public static Optional<Path> resolveFilesystemPath() {
        Optional<Path> local = resolvedFile;
        if (local == null) {
            synchronized (GeorgianPdfFont.class) {
                local = resolvedFile;
                if (local == null) {
                    local = findFilesystemCandidate();
                    resolvedFile = local;
                }
            }
        }
        return local;
    }

    /** Embeds a fresh {@link PDFont} into {@code document}, or empty if no font can be found at all. */
    public static Optional<PDFont> load(PDDocument document) throws IOException {
        try (InputStream bundled = GeorgianPdfFont.class.getResourceAsStream(CLASSPATH_FONT)) {
            if (bundled != null) {
                return Optional.of(PDType0Font.load(document, bundled));
            }
        }
        Optional<Path> path = resolveFilesystemPath();
        if (path.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(PDType0Font.load(document, path.get().toFile()));
    }

    private static boolean classpathFontExists() {
        return GeorgianPdfFont.class.getResource(CLASSPATH_FONT) != null;
    }

    private static Optional<Path> findFilesystemCandidate() {
        for (String candidate : FILESYSTEM_CANDIDATES) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }
}
