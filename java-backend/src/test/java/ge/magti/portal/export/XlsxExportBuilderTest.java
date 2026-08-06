package ge.magti.portal.export;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XlsxExportBuilderTest {

    @Test
    void writesHeaderAndDataRowsWithGeorgianTextRoundTripping() throws IOException {
        byte[] bytes = XlsxExportBuilder.build(
                "Compliance",
                List.of("თანამშრომელი", "სტატუსი"),
                List.of(List.of("ნინო ჩიტიშვილი", "read")));

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            assertEquals("Compliance", sheet.getSheetName());

            Row header = sheet.getRow(0);
            assertEquals("თანამშრომელი", header.getCell(0).getStringCellValue());
            assertEquals("სტატუსი", header.getCell(1).getStringCellValue());

            Row data = sheet.getRow(1);
            assertEquals("ნინო ჩიტიშვილი", data.getCell(0).getStringCellValue());
            assertEquals("read", data.getCell(1).getStringCellValue());
        }
    }

    @Test
    void formulaInjectionCellIsPrefixedWithQuoteInsideXlsxToo() throws IOException {
        byte[] bytes = XlsxExportBuilder.build("Export", List.of("Department"), List.of(List.of("=SUM(A1:A9)")));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Cell cell = wb.getSheetAt(0).getRow(1).getCell(0);
            assertTrue(cell.getStringCellValue().startsWith("'="));
        }
    }

    @Test
    void sheetTitleIsTruncatedTo31CharsMatchingExcelsLimit() throws IOException {
        String longTitle = "ეს არის ძალიან გრძელი სახელი ცხრილისთვის, 31 სიმბოლოზე მეტი ნამდვილად";
        byte[] bytes = XlsxExportBuilder.build(longTitle, List.of("H"), List.of());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertTrue(wb.getSheetAt(0).getSheetName().length() <= 31);
        }
    }
}
