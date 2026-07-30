package ge.magti.portal.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every case here was verified against real Python's
 * {@code (name or "").split(None, 1)} output first (not assumed), since
 * that idiom's edge-case behavior is genuinely easy to get wrong.
 */
class DisplayNameTest {

    @Test
    void blankOrWhitespaceOnlyProducesNoParts() {
        assertArrayEquals(new String[0], DisplayName.splitFirstLast(""));
        assertArrayEquals(new String[0], DisplayName.splitFirstLast("   "));
        assertArrayEquals(new String[0], DisplayName.splitFirstLast(null));
    }

    @Test
    void singleWordProducesOnlyAFirstName() {
        assertArrayEquals(new String[] {"Nika"}, DisplayName.splitFirstLast("Nika"));
    }

    @Test
    void twoWordsSplitOnTheWhitespaceBetweenThem() {
        assertArrayEquals(new String[] {"Nika", "Agdgomelashvili"}, DisplayName.splitFirstLast("Nika Agdgomelashvili"));
    }

    @Test
    void tabIsTreatedAsWhitespaceToo() {
        assertArrayEquals(new String[] {"Nika", "Agdgomelashvili"}, DisplayName.splitFirstLast("Nika\tAgdgomelashvili"));
    }

    @Test
    void leadingWhitespaceIsSkippedButInternalAndTrailingWhitespaceInTheRestIsNot() {
        assertArrayEquals(
                new String[] {"Nika", "Agdgomelashvili  extra  words"},
                DisplayName.splitFirstLast("  Nika   Agdgomelashvili  extra  words"));
        assertArrayEquals(
                new String[] {"Nika", "Agdgomelashvili   "},
                DisplayName.splitFirstLast("Nika Agdgomelashvili   "));
    }

    @Test
    void singleWordPlusTrailingWhitespaceProducesOnlyAFirstNameNotAnEmptyLastName() {
        // The one genuinely surprising case: Python's split(None, 1) on
        // "Nika " returns ['Nika'], not ['Nika', ''].
        assertArrayEquals(new String[] {"Nika"}, DisplayName.splitFirstLast("Nika "));
    }

    @Test
    void firstNameAndLastNameHelpersMatchThePythonFallbackExpressions() {
        String[] none = DisplayName.splitFirstLast("");
        String[] one = DisplayName.splitFirstLast("Nika");
        String[] two = DisplayName.splitFirstLast("Nika Agdgomelashvili");

        assertEquals("", DisplayName.firstName(none));
        assertEquals("", DisplayName.lastName(none));
        assertEquals("Nika", DisplayName.firstName(one));
        assertEquals("", DisplayName.lastName(one));
        assertEquals("Nika", DisplayName.firstName(two));
        assertEquals("Agdgomelashvili", DisplayName.lastName(two));
    }
}
