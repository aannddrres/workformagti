package ge.magti.portal.export;

import java.time.OffsetDateTime;

/**
 * One flattened ReadStatus+User+RequiredReading row, the shared shape
 * behind all 3 readings-export formats (routers/exports.py's
 * export_readings/export_readings_xlsx/export_readings_pdf). Each format
 * picks and formats its own subset of columns.
 */
public record ReadingExportRow(
        Long userId,
        String userName,
        String department,
        String itemType,
        Long itemId,
        String status,
        OffsetDateTime readAt,
        OffsetDateTime dueDate
) {
}
