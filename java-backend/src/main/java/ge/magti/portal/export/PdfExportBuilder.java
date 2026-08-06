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
 * Mirrors {@code _build_table_pdf} (routers/exports.py:206-243): a
 * paginated table PDF with a title, a red/white header row that repeats on
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
            float[] colWidths = computeColumnWidths(font, headers, rows, contentWidth);

            Page page = newPage(document, pageWidth, pageHeight);
            float y = pageHeight - MARGIN;
            y = drawTitle(page.stream, font, title, MARGIN, y);
            y -= 12f;
            y = drawHeaderRow(page.stream, font, headers, colWidths, MARGIN, y);

            int rowIndex = 0;
            for (List<Object> row : rows) {
                if (y - ROW_HEIGHT < MARGIN) {
                    page.stream.close();
                    page = newPage(document, pageWidth, pageHeight);
                    y = pageHeight - MARGIN;
                    y = drawHeaderRow(page.stream, font, headers, colWidths, MARGIN, y);
                }
                Color bg = (rowIndex % 2 == 0) ? Color.WHITE : ALT_ROW_BG;
                y = drawDataRow(page.stream, font, row, colWidths, MARGIN, y, bg);
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
            PDPageContentStream cs, PDFont font, List<String> headers, float[] colWidths, float x, float y) throws IOException {
        List<Object> cells = headers.stream().map(h -> (Object) h).toList();
        drawRow(cs, font, cells, colWidths, x, y, HEADER_BG, HEADER_TEXT, true);
        return y - ROW_HEIGHT;
    }

    private static float drawDataRow(
            PDPageContentStream cs, PDFont font, List<Object> cells, float[] colWidths, float x, float y, Color bg) throws IOException {
        drawRow(cs, font, cells, colWidths, x, y, bg, BODY_TEXT, false);
        return y - ROW_HEIGHT;
    }

    private static void drawRow(
            PDPageContentStream cs, PDFont font, List<Object> cells, float[] colWidths,
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
        float textBaselineY = rowTop - ROW_HEIGHT + (ROW_HEIGHT - FONT_SIZE) / 2f + 2f;
        for (int col = 0; col < colWidths.length; col++) {
            String raw = col < cells.size() && cells.get(col) != null ? String.valueOf(cells.get(col)) : "";
            float available = colWidths[col] - 2 * CELL_PADDING;
            String text = truncateToWidth(font, raw, available);
            float textWidth = stringWidth(font, text);
            float textX;
            if (centered) {
                textX = cellX + Math.max(CELL_PADDING, (colWidths[col] - textWidth) / 2f);
            } else {
                textX = cellX + CELL_PADDING;
            }
            cs.beginText();
            cs.setFont(font, FONT_SIZE);
            cs.setNonStrokingColor(textColor);
            cs.newLineAtOffset(textX, textBaselineY);
            cs.showText(text);
            cs.endText();
            cellX += colWidths[col];
        }
    }

    private static float[] computeColumnWidths(PDFont font, List<String> headers, List<List<Object>> rows, float contentWidth) throws IOException {
        int n = headers.size();
        float[] weights = new float[n];
        for (int i = 0; i < n; i++) {
            weights[i] = Math.max(MIN_COLUMN_WIDTH_CHARS, headers.get(i).length());
        }
        for (List<Object> row : rows) {
            for (int i = 0; i < n && i < row.size(); i++) {
                Object v = row.get(i);
                int len = v == null ? 0 : String.valueOf(v).length();
                weights[i] = Math.max(weights[i], len);
            }
        }
        float totalWeight = 0f;
        for (float w : weights) {
            totalWeight += w;
        }
        float[] widths = new float[n];
        for (int i = 0; i < n; i++) {
            widths[i] = contentWidth * (weights[i] / totalWeight);
        }
        return widths;
    }

    private static float stringWidth(PDFont font, String text) throws IOException {
        return font.getStringWidth(text) / 1000f * FONT_SIZE;
    }

    private static String truncateToWidth(PDFont font, String text, float maxWidth) throws IOException {
        if (maxWidth <= 0 || text.isEmpty()) {
            return "";
        }
        if (stringWidth(font, text) <= maxWidth) {
            return text;
        }
        // ASCII "..." rather than the U+2026 ellipsis glyph: Sylfaen (this
        // port's Windows-dev fallback font) doesn't reliably cover general
        // Unicode punctuation the way DejaVuSans does, and a missing glyph
        // throws IllegalArgumentException from PDFBox's showText.
        String ellipsis = "...";
        float ellipsisWidth = stringWidth(font, ellipsis);
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() > 0 && stringWidth(font, sb.toString()) + ellipsisWidth > maxWidth) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.isEmpty() ? "" : sb + ellipsis;
    }
}
