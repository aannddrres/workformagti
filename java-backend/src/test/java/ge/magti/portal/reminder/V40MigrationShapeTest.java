package ge.magti.portal.reminder;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V40MigrationShapeTest {

    private final String sql = readMigration();

    @Test
    void keepsAutomaticDeduplicationAndAllowsRecurringManualReminders() {
        assertTrue(sql.contains("ALTER TABLE reminders DROP CONSTRAINT uq_reminder_once"));
        assertEquals(3, occurrences(sql, "CASE WHEN reminder_type <> 'MANUAL'"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX uq_reminder_once"));
    }

    private static int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }

    private static String readMigration() {
        try {
            return Files.readString(Path.of("src/main/resources/db/migration/V40__allow_recurring_manual_reminders.sql"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
