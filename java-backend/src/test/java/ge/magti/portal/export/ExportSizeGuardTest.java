package ge.magti.portal.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExportSizeGuardTest {

    @Test
    void allowsWellBelowLimit() {
        assertDoesNotThrow(() -> ExportSizeGuard.checkSize(600));
    }

    @Test
    void allowsExactlyAtLimit() {
        assertDoesNotThrow(() -> ExportSizeGuard.checkSize(ExportSizeGuard.MAX_ROWS));
    }

    @Test
    void rejectsOneRowOverLimit() {
        ExportTooLargeException ex = assertThrows(
                ExportTooLargeException.class,
                () -> ExportSizeGuard.checkSize(ExportSizeGuard.MAX_ROWS + 1));
        assertEquals(ExportSizeGuard.MAX_ROWS + 1, ex.getRowCount());
        assertEquals(ExportSizeGuard.MAX_ROWS, ex.getMaxRows());
    }
}
