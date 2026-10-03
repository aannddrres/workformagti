package ge.magti.portal.export;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * A paginated table PDF with a title, a red/white header row that repeats on
 * every page (ReportLab's {@code repeatRows=1}), a 0.25pt grey grid, and
 * alternating white/light-grey row backgrounds.
 *
 * <p>PDFBox has no high-level {@code Table} flowable like ReportLab's
 * Platypus, so column widths, row heights, and page breaks are computed by
 * hand here rather than ported line-for-line. Column widths are weighted
 * by each column's longest cell (a simpler heuristic than ReportLab's own
 * auto-layout, but the acceptance bar this whole migration already uses
 * for table/diff rendering is matching semantics, not pixel-identical
 * output). Cells too wide for their column are truncated with an ellipsis
 * rather than wrapped, to keep row-height math (and therefore pagination)
 * simple and predictable.
 */
public final class PdfExportBuilder {

    private static final float MARGIN = 24f;
    private static final float FONT_SIZE = 9f;
    /** The smallest a wide table's text is set in to fit; below it, cells are cut instead. */
    private static final float MIN_FONT_SIZE = 7f;
    private static final float TITLE_FONT_SIZE = 14f;
    private static final float ROW_HEIGHT = 16f;
    private static final float CELL_PADDING = 4f;
    private static final float MIN_COLUMN_WIDTH_CHARS = 4f;

    private static final Color HEADER_BG = new Color(0xCC, 0x00, 0x00);
    private static final Color HEADER_TEXT = Color.WHITE;
    private static final Color GRID_COLOR = new Color(0x80, 0x80, 0x80);
    private static final Color ALT_ROW_BG = new Color(0xF5, 0xF5, 0xF5);
    private static final Color BODY_TEXT = Color.BLACK;
    private static final Color TITLE_TEXT = Color.BLACK;

    private PdfExportBuilder() {
    }

    public static byte[] build(String title, List<String> headers, List<List<Object>> rows) {
        try (PDDocument document = new PDDocument()) {
            PDFont font = GeorgianPdfFont.load(document).orElseThrow(PdfFontUnavailableException::new);

            float pageWidth = PDRectangle.A4.getHeight();
            float pageHeight = PDRectangle.A4.getWidth();
            float contentWidth = pageWidth - 2 * MARGIN;
            Layout layout = computeLayout(font, headers, rows, contentWidth);
            float[] colWidths = layout.widths();
            float size = layout.fontSize();

            Page page = newPage(document, pageWidth, pageHeight);
            float y = pageHeight - MARGIN;
            y = drawTitle(page.stream, font, title, MARGIN, y);
            y -= 12f;
            y = drawHeaderRow(page.stream, font, size, headers, colWidths, MARGIN, y);

            int rowIndex = 0;
            for (List<Object> row : rows) {
                if (y - ROW_HEIGHT < MARGIN) {
                    page.stream.close();
                    page = newPage(document, pageWidth, pageHeight);
                    y = pageHeight - MARGIN;
                    y = drawHeaderRow(page.stream, font, size, headers, colWidths, MARGIN, y);
                }
                Color bg = (rowIndex % 2 == 0) ? Color.WHITE : ALT_ROW_BG;
                y = drawDataRow(page.stream, font, size, row, colWidths, MARGIN, y, bg);
                rowIndex++;
            }
            page.stream.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Page(PDPage pdPage, PDPageContentStream stream) {
    }

    private static Page newPage(PDDocument document, float width, float height) throws IOException {
        PDPage pdPage = new PDPage(new PDRectangle(width, height));
        document.addPage(pdPage);
        return new Page(pdPage, new PDPageContentStream(document, pdPage));
    }

    private static float drawTitle(PDPageContentStream cs, PDFont font, String title, float x, float y) throws IOException {
        cs.beginText();
        cs.setFont(font, TITLE_FONT_SIZE);
        cs.setNonStrokingColor(TITLE_TEXT);
        cs.newLineAtOffset(x, y - TITLE_FONT_SIZE);
        cs.showText(title == null ? "" : title);
        cs.endText();
        return y - TITLE_FONT_SIZE - 4f;
    }

    private static float drawHeaderRow(
            PDPageContentStream cs, PDFont font, float size, List<String> headers, float[] colWidths, float x, float y) throws IOException {
        List<Object> cells = headers.stream().map(h -> (Object) h).toList();
        drawRow(cs, font, size, cells, colWidths, x, y, HEADER_BG, HEADER_TEXT, true);
        return y - ROW_HEIGHT;
    }

    private static float drawDataRow(
            PDPageContentStream cs, PDFont font, float size, List<Object> cells, float[] colWidths, float x, float y, Color bg) throws IOException {
        drawRow(cs, font, size, cells, colWidths, x, y, bg, BODY_TEXT, false);
        return y - ROW_HEIGHT;
    }

    private static void drawRow(
            PDPageContentStream cs, PDFont font, float size, List<Object> cells, float[] colWidths,
            float x, float y, Color background, Color textColor, boolean centered) throws IOException {
        float rowTop = y;
        float rowWidth = 0f;
        for (float w : colWidths) {
            rowWidth += w;
        }

        cs.setNonStrokingColor(background);
        cs.addRect(x, rowTop - ROW_HEIGHT, rowWidth, ROW_HEIGHT);
        cs.fill();

        cs.setStrokingColor(GRID_COLOR);
        cs.setLineWidth(0.25f);
        float gridX = x;
        for (int i = 0; i <= colWidths.length; i++) {
            cs.moveTo(gridX, rowTop);
            cs.lineTo(gridX, rowTop - ROW_HEIGHT);
            if (i < colWidths.length) {
                gridX += colWidths[i];
            }
        }
        cs.moveTo(x, rowTop);
        cs.lineTo(x + rowWidth, rowTop);
        cs.moveTo(x, rowTop - ROW_HEIGHT);
        cs.lineTo(x + rowWidth, rowTop - ROW_HEIGHT);
        cs.stroke();

        float cellX = x;
        float textBaselineY = rowTop - ROW_HEIGHT + (ROW_HEIGHT - size) / 2f + 2f;
        for (int col = 0; col < colWidths.length; col++) {
            String raw = col < cells.size() && cells.get(col) != null ? String.valueOf(cells.get(col)) : "";
            float available = colWidths[col] - 2 * CELL_PADDING;
            String text = truncateToWidth(font, size, raw, available);
            float textWidth = stringWidth(font, size, text);
            float textX;
            if (centered) {
                textX = cellX + Math.max(CELL_PADDING, (colWidths[col] - textWidth) / 2f);
            } else {
                textX = cellX + CELL_PADDING;
            }
            cs.beginText();
            cs.setFont(font, size);
            cs.setNonStrokingColor(textColor);
            cs.newLineAtOffset(textX, textBaselineY);
            cs.showText(text);
            cs.endText();
            cellX += colWidths[col];
        }
    }

    private record Layout(float[] widths, float fontSize) {
    }

    /**
     * Each column gets the width its longest cell needs, as measured in the
     * font. When the page is too narrow the type shrinks first, down to
     * {@link #MIN_FONT_SIZE}; if that is still not enough, the columns that
     * need least keep their full width and only the widest share what is left.
     *
     * <p>Widths used to be split in proportion to character counts at a fixed
     * 9 pt. Once the readings export carried eight columns, one of them a
     * title (PO-13, 2026-10-02), that cut every column short together:
     * names, departments and the status lost their ends to make room for the
     * title. Now the title is the one that ends in "...".
     */
    private static Layout computeLayout(PDFont font, List<String> headers, List<List<Object>> rows, float contentWidth) throws IOException {
        int n = headers.size();
        float size = FONT_SIZE;
        float[] text = new float[n];
        for (int i = 0; i < n; i++) {
            text[i] = Math.max(MIN_COLUMN_WIDTH_CHARS * stringWidth(font, size, "0"), stringWidth(font, size, headers.get(i)));
        }
        for (List<Object> row : rows) {
            for (int i = 0; i < n && i < row.size(); i++) {
                Object v = row.get(i);
                if (v != null) {
                    text[i] = Math.max(text[i], stringWidth(font, size, String.valueOf(v)));
                }
            }
        }
        float textTotal = 0f;
        for (float w : text) {
            textTotal += w;
        }
        // Shrink the type before cutting anything: 7 pt still reads on paper.
        float room = contentWidth - n * 2 * CELL_PADDING;
        if (textTotal > room) {
            size = Math.max(MIN_FONT_SIZE, FONT_SIZE * room / textTotal);
        }
        float[] natural = new float[n];
        float total = 0f;
        for (int i = 0; i < n; i++) {
            // One point spare: a cell exactly as wide as its text is measured
            // again at draw time and rounding can make it "not fit" ("ჯგუ...").
            natural[i] = text[i] * size / FONT_SIZE + 2 * CELL_PADDING + 1f;
            total += natural[i];
        }
        float[] widths = new float[n];
        if (total <= contentWidth) {
            for (int i = 0; i < n; i++) {
                widths[i] = natural[i] * contentWidth / total;
            }
            return new Layout(widths, size);
        }
        boolean[] fixed = new boolean[n];
        float remaining = contentWidth;
        int open = n;
        boolean changed = true;
        while (changed && open > 0) {
            changed = false;
            float share = remaining / open;
            for (int i = 0; i < n; i++) {
                if (!fixed[i] && natural[i] <= share) {
                    widths[i] = natural[i];
                    fixed[i] = true;
                    remaining -= natural[i];
                    open--;
                    changed = true;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            if (!fixed[i]) {
                widths[i] = remaining / open;
            }
        }
        return new Layout(widths, size);
    }

    private static float stringWidth(PDFont font, float size, String text) throws IOException {
        return font.getStringWidth(text) / 1000f * size;
    }

    private static String truncateToWidth(PDFont font, float size, String text, float maxWidth) throws IOException {
        if (maxWidth <= 0 || text.isEmpty()) {
            return "";
        }
        if (stringWidth(font, size, text) <= maxWidth) {
            return text;
        }
        // ASCII "..." rather than the U+2026 ellipsis glyph: Sylfaen (this
        // port's Windows-dev fallback font) doesn't reliably cover general
        // Unicode punctuation the way DejaVuSans does, and a missing glyph
        // throws IllegalArgumentException from PDFBox's showText.
        String ellipsis = "...";
        float ellipsisWidth = stringWidth(font, size, ellipsis);
        // The longest prefix that fits, found by halving. Dropping one
        // character at a time and measuring again made each long title cost
        // hundreds of measurements, and a 5,400-row readings PDF took 91 s
        // once titles became a column (2026-10-02).
        int fits = 0;
        int tooLong = text.length();
        while (tooLong - fits > 1) {
            int mid = (fits + tooLong) >>> 1;
            if (stringWidth(font, size, text.substring(0, mid)) + ellipsisWidth <= maxWidth) {
                fits = mid;
            } else {
                tooLong = mid;
            }
        }
        if (fits > 0 && Character.isHighSurrogate(text.charAt(fits - 1))) {
            fits--;
        }
        return fits == 0 ? "" : text.substring(0, fits) + ellipsis;
    }
}
