package ge.magti.portal.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExportCellSanitizerTest {

    @Test
    void leavesOrdinaryStringsUntouched() {
        assertEquals("გიორგი", ExportCellSanitizer.sanitize("გიორგი"));
    }

    @Test
    void prependsQuoteForEachFormulaTriggerCharacter() {
        // The exact 6-character set -- checked together so this test breaks
        // if that set is ever changed without updating this test.
        String[] triggeringValues = {"=SUM(A1:A9)", "+1", "-1", "@cmd", "\ttabbed", "\rcr"};
        for (String value : triggeringValues) {
            assertEquals("'" + value, ExportCellSanitizer.sanitize(value), "for value: " + value);
        }
    }

    @Test
    void nonStringValuesPassThroughUnchanged() {
        assertEquals(42, ExportCellSanitizer.sanitize(42));
        assertNull(ExportCellSanitizer.sanitize(null));
    }

    @Test
    void emptyStringIsUnaffected() {
        assertEquals("", ExportCellSanitizer.sanitize(""));
    }
}
