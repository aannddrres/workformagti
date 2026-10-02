package ge.magti.portal.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DepartmentMatcherTest {

    /**
     * Codepoints verified independently against the live compliance_utils.py
     * source (see DepartmentMatcher's class Javadoc) -- if this source file
     * were ever read with the wrong encoding, this fails loudly here instead
     * of silently corrupting department matching.
     */
    @Test
    void thisSourceFileReadsGeorgianTextAsCorrectUtf8() {
        String keyword = "ჯგუფი";

        assertEquals(5, keyword.length());
        assertEquals(0x10ef, keyword.codePointAt(0));
        assertEquals(0x10d2, keyword.codePointAt(1));
        assertEquals(0x10e3, keyword.codePointAt(2));
        assertEquals(0x10e4, keyword.codePointAt(3));
        assertEquals(0x10d8, keyword.codePointAt(4));
    }

    @Test
    void emDashIsTheCanonicalDelimiter() {
        DepartmentGroup result = DepartmentMatcher.splitGroup("საინფორმაციო სამსახური — ჯგუფი 01");

        assertEquals("საინფორმაციო სამსახური", result.prefix());
        assertEquals("ჯგუფი 01", result.groupLabel());
    }

    @Test
    void asciiHyphenFallsBackToDashSplit() {
        DepartmentGroup result = DepartmentMatcher.splitGroup("ტექნიკური - ჯგუფი 01");

        assertEquals("ტექნიკური", result.prefix());
        assertEquals("ჯგუფი 01", result.groupLabel());
    }

    @Test
    void keywordAnchorUsedWhenNoDashPresent() {
        DepartmentGroup result = DepartmentMatcher.splitGroup("ტექნიკური დეპარტამენტი ჯგუფი 01");

        assertEquals("ტექნიკური დეპარტამენტი", result.prefix());
        assertEquals("ჯგუფი 01", result.groupLabel());
    }

    @Test
    void noDelimiterAtAllReturnsWholeStringAsBothPrefixAndLabel() {
        DepartmentGroup result = DepartmentMatcher.splitGroup("ოფისი");

        assertEquals("ოფისი", result.prefix());
        assertEquals("ოფისი", result.groupLabel());
    }

    @Test
    void nullAndBlankInputReturnEmptyGroup() {
        assertEquals(new DepartmentGroup("", ""), DepartmentMatcher.splitGroup(null));
        assertEquals(new DepartmentGroup("", ""), DepartmentMatcher.splitGroup("   "));
    }

    @Test
    void allTargetMatchesAnyDepartment() {
        assertTrue(DepartmentMatcher.matches("whatever", List.of("All")));
    }

    @Test
    void exactMatchWorks() {
        assertTrue(DepartmentMatcher.matches("ოფისი", List.of("ოფისი")));
    }

    @Test
    void prefixMatchesASubGroup() {
        assertTrue(DepartmentMatcher.matches("ტექნიკური — ჯგუფი 03", List.of("ტექნიკური")));
    }

    @Test
    void nonMatchingDepartmentReturnsFalse() {
        assertFalse(DepartmentMatcher.matches("ოფისი", List.of("ტექნიკური")));
    }

    @Test
    void emptyTargetDoesNotMatchViaEmptyPrefix() {
        // This user department's prefix is "" (the delimiter sits at position
        // 0) -- without Python's "t and ..." truthiness guard on the target,
        // an empty target would spuriously match an empty prefix here.
        assertFalse(DepartmentMatcher.matches("— ჯგუფი 01", List.of("")));
    }

    /** Mutation testing, 2026-10-02: the plain-dash split had only been reached by strings the keyword rule took first. */
    @Test
    void aHyphenWithoutTheGroupKeywordStillSplitsDepartmentFromGroup() {
        assertEquals(new DepartmentGroup("ტექნიკური", "ღამის ცვლა"), DepartmentMatcher.splitGroup("ტექნიკური - ღამის ცვლა"));
        assertTrue(DepartmentMatcher.matches("ტექნიკური - ღამის ცვლა", List.of("ტექნიკური")));
    }

    /** A bare "ჯგუფი 03" names a group of no department: it is its own prefix, never the empty one. */
    @Test
    void aGroupNameAloneIsItsOwnDepartment() {
        assertEquals(new DepartmentGroup("ჯგუფი 03", "ჯგუფი 03"), DepartmentMatcher.splitGroup("ჯგუფი 03"));
    }
}
