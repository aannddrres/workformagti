package ge.magti.portal.reminder;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V39MigrationShapeTest {

    private final String sql = readMigration();

    @Test
    void createsIndependentReminderLedgerAndDatabaseDeduplication() {
        assertTrue(sql.contains("CREATE TABLE reminders"));
        assertTrue(sql.contains("CONSTRAINT uq_reminder_once UNIQUE"));
        assertTrue(sql.contains("required_reading_id, recipient_user_id, reminder_type"));
        assertTrue(sql.contains("ON DELETE SET NULL"));
    }

    @Test
    void enablesTruthfulSystemAuditWithoutTouchingLegacyMessageData() {
        assertTrue(sql.contains("ALTER TABLE audit_logs MODIFY (admin_id NULL)"));
        assertFalse(sql.toUpperCase().contains("DROP TABLE MESSAGES"));
        assertFalse(sql.toUpperCase().contains("INSERT INTO REMINDERS"),
                "legacy messages and readings must not be guessed into reminder rows");
    }

    private static String readMigration() {
        try {
            return Files.readString(Path.of("src/main/resources/db/migration/V39__create_reminders.sql"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
