package ge.magti.portal.compliance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PO-40: a mandatory article gets one reading per department of its audience,
 * and nobody is addressed twice by the same article.
 */
class MandatoryReachTargetsTest {

    @Test
    void eachDepartmentOfTheAudienceGetsItsOwnReading() {
        assertEquals(List.of("საინფორმაციო", "ტექნიკური"),
                MandatoryReach.readingTargets(List.of("საინფორმაციო", "ტექნიკური")));
    }

    @Test
    void allCoversEverythingElse() {
        assertEquals(List.of("All"), MandatoryReach.readingTargets(List.of("ტექნიკური", "All", "ოფისი")));
    }

    @Test
    void aDepartmentCoversItsOwnGroupsButGroupsOfAnAbsentParentStay() {
        assertEquals(List.of("ტექნიკური", "ოფისი — ჯგუფი 01", "ოფისი — ჯგუფი 02"),
                MandatoryReach.readingTargets(List.of(
                        "ტექნიკური — ჯგუფი 03", "ტექნიკური", "ოფისი — ჯგუფი 01", "ოფისი — ჯგუფი 02")));
    }

    @Test
    void blanksAndRepeatsAreDropped() {
        assertEquals(List.of("ოფისი"), MandatoryReach.readingTargets(java.util.Arrays.asList("ოფისი", "", null, "ოფისი")));
    }
}
