package ge.magti.portal.export;

import java.util.Map;

/** Human-readable labels at the file-export boundary; source codes stay unchanged. */
public final class ExportDisplayLabels {

    private static final Map<String, String> ITEM_TYPES = Map.of(
            "article", "სტატია", "news", "სიახლე");
    private static final Map<String, String> READING_STATUSES = Map.of(
            "read", "წაკითხულია", "unread", "წაუკითხავია", "overdue", "ვადაგადაცილებულია");
    private static final Map<String, String> EVIDENCE_TYPES = Map.of(
            "ARTICLE_RECEIPT", "სტატიის გაცნობის ჩანაწერი",
            "REQUIRED_READING", "სავალდებულო გაცნობა");

    private ExportDisplayLabels() {}

    public static String itemType(String code) {
        return label(code, ITEM_TYPES);
    }

    public static String readingStatus(String code) {
        return label(code, READING_STATUSES);
    }

    public static String evidenceType(String code) {
        return label(code, EVIDENCE_TYPES);
    }

    public static String missingDate() {
        return "არ არის";
    }

    private static String label(String code, Map<String, String> labels) {
        if (code == null || code.isBlank()) {
            return "არ არის";
        }
        return labels.getOrDefault(code, "უცნობი კოდი (" + code + ")");
    }
}
