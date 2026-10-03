package ge.magti.portal.reminder;

import ge.magti.portal.domain.ReminderType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** PO-58: the due-soon reminder names its deadline, in Tbilisi time. */
class ReminderTextTest {

    @Test
    void dueSoonNamesTheDeadlineInTbilisiTime() {
        // 14:00 UTC is 18:00 in Tbilisi.
        String text = ReminderService.contentFor(ReminderType.DUE_SOON, "როუმინგი",
                OffsetDateTime.parse("2026-10-04T14:00:00Z"));
        assertEquals("სავალდებულო მასალის „როუმინგი“ ვადა იწურება 2026-10-04 18:00-ზე.", text);
    }
}
