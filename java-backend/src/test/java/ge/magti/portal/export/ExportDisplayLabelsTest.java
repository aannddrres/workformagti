package ge.magti.portal.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportDisplayLabelsTest {

    @Test
    void knownStatusesTypesAndEmptyDatesAreReadable() {
        assertEquals("სტატია", ExportDisplayLabels.itemType("article"));
        assertEquals("სიახლე", ExportDisplayLabels.itemType("news"));
        assertEquals("წაკითხულია", ExportDisplayLabels.readingStatus("read"));
        assertEquals("წაუკითხავია", ExportDisplayLabels.readingStatus("unread"));
        assertEquals("ვადაგადაცილებულია", ExportDisplayLabels.readingStatus("overdue"));
        assertEquals("არ არის", ExportDisplayLabels.missingDate());
    }

    @Test
    void unknownStoredCodesRemainVisibleForInvestigation() {
        assertTrue(ExportDisplayLabels.readingStatus("future-status").contains("future-status"));
        assertTrue(ExportDisplayLabels.itemType("future-type").contains("future-type"));
        assertTrue(ExportDisplayLabels.evidenceType("FUTURE_EVENT").contains("FUTURE_EVENT"));
    }
}
