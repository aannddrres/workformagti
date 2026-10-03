package ge.magti.portal.export;

import java.time.OffsetDateTime;

/**
 * One flattened ReadStatus+User+RequiredReading row, the shared shape
 * behind all 3 readings-export formats (CSV, XLSX, PDF). All three
 * write the same eight PO-13 columns; {@code userId} and {@code itemId}
 * identify the row in code and are never written to a file (PO-13 leaves
 * employee and material IDs out).
 */
public record ReadingExportRow(
        Long userId,
        String userName,
        String department,
        String group,
        String itemType,
        Long itemId,
        String itemTitle,
        String status,
        OffsetDateTime readAt,
        OffsetDateTime dueDate
) {
}
