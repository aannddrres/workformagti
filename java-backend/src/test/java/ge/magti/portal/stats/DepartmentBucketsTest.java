package ge.magti.portal.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Mirrors tests/test_department_stats.py::test_match_department_bucket's
 * exact assertions, cross-checked against the live Python test suite, not
 * just this class's own source.
 */
class DepartmentBucketsTest {

    @Test
    void exactWhitelistEntriesMatchThemselves() {
        assertEquals("ტექნიკური", DepartmentBuckets.match("ტექნიკური"));
        assertEquals("საინფორმაციო", DepartmentBuckets.match("საინფორმაციო"));
        assertEquals("ოფისი", DepartmentBuckets.match("ოფისი"));
    }

    @Test
    void longerPrefixMatchesViaStartsWith() {
        assertEquals("ტექნიკური", DepartmentBuckets.match("ტექნიკური სამსახური"));
    }

    @Test
    void shortenedSynonymMatchesInformational() {
        assertEquals("საინფორმაციო", DepartmentBuckets.match("საინფო"));
    }

    @Test
    void officePrefixExtractedBeforeMatching() {
        assertEquals("ოფისი", DepartmentBuckets.match("ოფისი — ჯგუფი 01".split("—")[0].strip()));
    }

    @Test
    void unrecognizedOrBlankPrefixReturnsNull() {
        assertNull(DepartmentBuckets.match("All"));
        assertNull(DepartmentBuckets.match(""));
        assertNull(DepartmentBuckets.match(null));
    }
}
