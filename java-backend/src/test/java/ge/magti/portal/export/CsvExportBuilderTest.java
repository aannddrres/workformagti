package ge.magti.portal.export;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvExportBuilderTest {

    @Test
    void headerAndPlainRowsAreCommaJoinedWithCrlf() {
        String csv = CsvExportBuilder.build(
                List.of("User ID", "User Name"),
                List.of(List.of(1, "ნინო")));
        assertEquals("User ID,User Name\r\n1,ნინო\r\n", csv);
    }

    @Test
    void fieldsContainingCommaOrQuoteAreQuotedAndEscaped() {
        String csv = CsvExportBuilder.build(
                List.of("Name"),
                List.of(List.of("Doe, John \"the boss\"")));
        assertEquals("Name\r\n\"Doe, John \"\"the boss\"\"\"\r\n", csv);
    }

    @Test
    void formulaInjectionCellsArePrefixedWithQuote() {
        String csv = CsvExportBuilder.build(
                List.of("Department"),
                List.of(List.of("=cmd|'/c calc'!A1")));
        assertTrue(csv.contains("'=cmd"));
    }

    @Test
    void headerRowItselfIsNeverSanitized() {
        // Headers are static literals this codebase controls, never sanitized.
        String csv = CsvExportBuilder.build(List.of("=Header"), List.of());
        assertTrue(csv.startsWith("=Header\r\n"));
    }
}
