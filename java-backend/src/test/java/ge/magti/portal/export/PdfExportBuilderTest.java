package ge.magti.portal.export;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfExportBuilderTest {

    @Test
    void singlePageRenderContainsTitleHeaderAndGeorgianDataText() throws IOException {
        assumeTrue(GeorgianPdfFont.resolvePath().isPresent(), "no Georgian-capable TTF on this machine");

        byte[] pdf = PdfExportBuilder.build(
                "სავალდებულოდ გასაცნობი სტატუსი",
                List.of("თანამშრომელი", "სტატუსი"),
                List.of(List.of("ნინო ჩიტიშვილი", "read")));

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("სავალდებულოდ"));
            assertTrue(text.contains("თანამშრომელი"));
            assertTrue(text.contains("ნინო ჩიტიშვილი"));
        }
    }

    @Test
    void manyRowsPaginateAndRepeatTheHeaderRow() throws IOException {
        assumeTrue(GeorgianPdfFont.resolvePath().isPresent(), "no Georgian-capable TTF on this machine");

        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            rows.add(List.of("მომხმარებელი " + i, "read"));
        }
        byte[] pdf = PdfExportBuilder.build("დიდი სია", List.of("სახელი", "სტატუსი"), rows);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertTrue(document.getNumberOfPages() > 1, "80 rows should overflow one landscape A4 page");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(2);
            stripper.setEndPage(2);
            String page2Text = stripper.getText(document);
            assertTrue(page2Text.contains("სახელი"), "header row should repeat on subsequent pages");
        }
    }

    @Test
    void longCellIsTruncatedRatherThanOverflowingIntoTheNextColumn() throws IOException {
        assumeTrue(GeorgianPdfFont.resolvePath().isPresent(), "no Georgian-capable TTF on this machine");

        // Two columns of comparably long content: proportional column-width
        // weighting only gives each ~half the page, which (minus cell padding)
        // isn't enough for either full string -- unlike a single dominant
        // column, which would just claim ~all the width and never truncate.
        String longA = "ძალიან ძალიან ძალიან გრძელი სახელი რომელიც ნამდვილად არ ეტევა ერთ სვეტში ბოლომდე";
        String longB = "ასევე ძალიან გრძელი დეპარტამენტის სახელწოდება, რომელიც კონკურენციას გაუწევს პირველ სვეტს";
        byte[] pdf = PdfExportBuilder.build(
                "Export", List.of("სახელი", "დეპარტამენტი"), List.of(List.of(longA, longB)));

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("..."), "an overlong cell should be truncated with an ellipsis");
        }
    }
}
