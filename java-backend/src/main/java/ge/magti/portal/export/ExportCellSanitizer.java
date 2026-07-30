package ge.magti.portal.export;

/**
 * Mirrors routers/exports.py's {@code _sanitize_cell}
 * (routers/exports.py:59-62): a spreadsheet-formula-injection guard
 * (CWE-1236). A free-text cell (a user's name, department, or a message
 * title) starting with {@code =}, {@code +}, {@code -}, {@code @}, a tab, or
 * a carriage return is read as a formula by Excel/LibreOffice on open; a
 * leading single quote forces it back to literal text in both CSV and XLSX.
 *
 * <p>Deliberately its own small, reusable class rather than inlined into
 * each export path -- the whole reason to have it separate is bug #8:
 * routers/audit_logs.py's CSV export (routers/audit_logs.py:305-312) builds
 * its rows without ever calling this sanitizer, unlike every export in
 * routers/exports.py, which is a real, live formula-injection gap on the
 * Python side today. Not fixed on the Python side by this port (out of
 * scope -- this is the Java migration, not a Python patch), but the Java
 * port's audit-log export, whenever it's written, has one shared sanitizer
 * to call so the same gap can't quietly reappear here.
 */
public final class ExportCellSanitizer {

    private static final String[] FORMULA_TRIGGER_PREFIXES = {"=", "+", "-", "@", "\t", "\r"};

    private ExportCellSanitizer() {
    }

    public static Object sanitize(Object value) {
        if (value instanceof String text) {
            for (String prefix : FORMULA_TRIGGER_PREFIXES) {
                if (text.startsWith(prefix)) {
                    return "'" + text;
                }
            }
        }
        return value;
    }
}
