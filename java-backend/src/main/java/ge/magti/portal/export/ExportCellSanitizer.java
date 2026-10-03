package ge.magti.portal.export;

/**
 * A spreadsheet-formula-injection guard
 * (CWE-1236). A free-text cell (a user's name, department, or a message
 * title) starting with {@code =}, {@code +}, {@code -}, {@code @}, a tab, or
 * a carriage return is read as a formula by Excel/LibreOffice on open; a
 * leading single quote forces it back to literal text in both CSV and XLSX.
 *
 * <p>Deliberately its own small, reusable class rather than inlined into
 * each export path -- the whole reason to have it separate is bug #8: the
 * original audit-log CSV export built its rows without ever calling this
 * sanitizer, unlike every other export, which was a real formula-injection
 * gap. The audit-log export has one shared sanitizer to call so the same
 * gap can't quietly reappear here.
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
