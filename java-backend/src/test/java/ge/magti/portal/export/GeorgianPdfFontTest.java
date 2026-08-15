package ge.magti.portal.export;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR-02's regression net. The bug was not "the font-loading code is wrong" --
 * it was "nothing anywhere asserted a font would be present at runtime", so a
 * runtime image with no fonts installed shipped and every PDF export failed.
 * These assertions are what makes that a build failure instead.
 */
class GeorgianPdfFontTest {

    /** Georgian block U+10D0..U+10F0, plus a Latin word to prove mixed text works. */
    private static final String GEORGIAN_SAMPLE = "თანამშრომელი";

    @Test
    void theFontIsBundledOnTheClasspath() throws IOException {
        try (InputStream bundled = GeorgianPdfFont.class.getResourceAsStream(GeorgianPdfFont.CLASSPATH_FONT)) {
            assertNotNull(bundled,
                    GeorgianPdfFont.CLASSPATH_FONT + " must be packaged in the jar -- without it, PDF export "
                            + "depends on the base image again, which is PR-02");
            assertTrue(bundled.readAllBytes().length > 100_000, "the packaged font looks truncated");
        }
    }

    /**
     * The assertion that would have failed in CI before the fix. Deliberately
     * not conditional on anything: a machine where this cannot resolve is a
     * machine where every PDF export is broken, and that must be loud.
     */
    @Test
    void aFontIsAlwaysAvailableToLoad() {
        assertTrue(GeorgianPdfFont.isAvailable());
    }

    @Test
    void loadEmbedsAFontIntoTheDocument() throws IOException {
        try (PDDocument document = new PDDocument()) {
            Optional<PDFont> font = GeorgianPdfFont.load(document);
            assertTrue(font.isPresent());
            assertNotNull(font.get().getName());
        }
    }

    /**
     * The real acceptance criterion: not "a font loaded" but "Georgian
     * survives the round trip". A Latin-only font would satisfy every
     * assertion above and still render the entire portal's reports as
     * blanks or question marks.
     */
    @Test
    void georgianTextSurvivesTheRenderAndExtractRoundTrip() throws IOException {
        byte[] pdf = PdfExportBuilder.build(
                GEORGIAN_SAMPLE, List.of(GEORGIAN_SAMPLE), List.of(List.of("ნინო ჩიტიშვილი", "read")));

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains(GEORGIAN_SAMPLE),
                    "the bundled font must actually carry Georgian glyphs, not merely load");
            assertTrue(text.contains("ნინო ჩიტიშვილი"));
        }
    }

    /**
     * Pins the mistake the original fix direction would have made: Alpine
     * ships DejaVu at a path none of the old Debian-shaped candidates
     * matched, so "apk add ttf-dejavu" alone would have looked like a fix
     * and changed nothing.
     */
    @Test
    void theAlpineFontPathIsAmongTheFallbackCandidates() throws Exception {
        java.lang.reflect.Field field = GeorgianPdfFont.class.getDeclaredField("FILESYSTEM_CANDIDATES");
        field.setAccessible(true);
        List<String> candidates = List.of((String[]) field.get(null));

        assertEquals(1, candidates.stream().filter(c -> c.equals("/usr/share/fonts/ttf-dejavu/DejaVuSans.ttf")).count(),
                "Alpine's own DejaVu path must be a candidate -- its absence is what made the obvious fix a no-op");
    }
}
