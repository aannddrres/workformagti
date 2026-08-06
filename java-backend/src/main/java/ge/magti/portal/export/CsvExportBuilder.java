package ge.magti.portal.export;

import java.util.List;

/**
 * Mirrors export_readings' CSV assembly (routers/exports.py:101-116) using
 * Python's {@code csv.writer} defaults: comma-separated, {@code \r\n} line
 * endings, fields quoted only when they contain a comma/quote/newline, and
 * a doubled quote to escape an embedded quote. No third-party CSV library
 * needed for a format this small. Header cells are written as-is (Python
 * never sanitizes the header row); data cells go through
 * {@link ExportCellSanitizer}.
 */
public final class CsvExportBuilder {

    private CsvExportBuilder() {
    }

    public static String build(List<String> headers, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildHeaderRow(headers));
        for (List<Object> row : rows) {
            sb.append(buildDataRow(row));
        }
        return sb.toString();
    }

    /** Header cells, written as-is (never sanitized) -- for callers streaming rows one at a time. */
    public static String buildHeaderRow(List<String> headers) {
        StringBuilder sb = new StringBuilder();
        writeRow(sb, headers);
        return sb.toString();
    }

    /** One sanitized data row -- for callers streaming rows one at a time. */
    public static String buildDataRow(List<Object> row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(ExportCellSanitizer.sanitize(row.get(i))));
        }
        sb.append("\r\n");
        return sb.toString();
    }

    private static void writeRow(StringBuilder sb, List<?> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(values.get(i)));
        }
        sb.append("\r\n");
    }

    private static String escape(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needsQuote) {
            return s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
