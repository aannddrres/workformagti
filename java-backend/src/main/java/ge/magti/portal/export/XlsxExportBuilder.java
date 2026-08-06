package ge.magti.portal.export;

import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Mirrors {@code _build_table_xlsx} (routers/exports.py:321-344): a single
 * styled sheet -- bold white-on-red header row, autosized columns (capped
 * at 40 characters wide), data cells run through {@link
 * ExportCellSanitizer}. {@code XSSFWorkbook} (not the streaming {@code
 * SXSSFWorkbook}) is fine here -- {@link ExportSizeGuard} already caps
 * every export at 20,000 rows before this is called.
 */
public final class XlsxExportBuilder {

    private static final int MAX_COLUMN_WIDTH_CHARS = 40;

    private XlsxExportBuilder() {
    }

    public static byte[] build(String title, List<String> headers, List<List<Object>> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            String sheetName = title == null || title.isBlank() ? "Export" : title;
            if (sheetName.length() > 31) {
                sheetName = sheetName.substring(0, 31);
            }
            XSSFSheet sheet = workbook.createSheet(sheetName);

            XSSFFont headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(new XSSFColor(Color.WHITE, null));
            XSSFCellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(new XSSFColor(new Color(0xCC, 0x00, 0x00), null));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            XSSFRow headerRow = sheet.createRow(0);
            int[] maxWidths = new int[headers.size()];
            for (int col = 0; col < headers.size(); col++) {
                XSSFCell cell = headerRow.createCell(col);
                cell.setCellValue(headers.get(col));
                cell.setCellStyle(headerStyle);
                maxWidths[col] = headers.get(col).length();
            }

            for (int r = 0; r < rows.size(); r++) {
                XSSFRow row = sheet.createRow(r + 1);
                List<Object> values = rows.get(r);
                for (int col = 0; col < values.size(); col++) {
                    Object sanitized = ExportCellSanitizer.sanitize(values.get(col));
                    String text = sanitized == null ? "" : String.valueOf(sanitized);
                    row.createCell(col).setCellValue(text);
                    if (col < maxWidths.length) {
                        maxWidths[col] = Math.max(maxWidths[col], text.length());
                    }
                }
            }

            for (int col = 0; col < maxWidths.length; col++) {
                int widthChars = Math.min(maxWidths[col] + 2, MAX_COLUMN_WIDTH_CHARS);
                sheet.setColumnWidth(col, widthChars * 256);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
