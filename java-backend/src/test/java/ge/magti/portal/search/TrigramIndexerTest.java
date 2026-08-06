package ge.magti.portal.search;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrigramIndexerTest {

    @Test
    void emptyForNullOrShortInput() {
        assertTrue(TrigramIndexer.extract(null).isEmpty());
        assertTrue(TrigramIndexer.extract("").isEmpty());
        assertTrue(TrigramIndexer.extract("a").isEmpty());
        assertTrue(TrigramIndexer.extract("ab").isEmpty());
    }

    @Test
    void extractsSlidingWindowOfThreeCharacters() {
        Set<String> trigrams = TrigramIndexer.extract("abcd");

        assertEquals(Set.of("abc", "bcd"), trigrams);
    }

    @Test
    void lowercasesBeforeExtracting() {
        assertEquals(TrigramIndexer.extract("ABC"), TrigramIndexer.extract("abc"));
    }

    /**
     * Georgian (Mkhedruli) is a unicase script -- lowercasing is a no-op for
     * it, but the multi-byte UTF-8 encoding must not corrupt char-based
     * substring slicing. This is the exact word Oracle Text's own wildcard
     * substring index proved unreliable on during live testing.
     */
    @Test
    void handlesGeorgianTextCorrectly() {
        Set<String> trigrams = TrigramIndexer.extract("კონფიგურაცია");

        assertTrue(trigrams.contains("კონ"));
        assertTrue(trigrams.contains("ონფ"));
        assertTrue(trigrams.contains("ფიგ"));
        assertTrue(trigrams.contains("რაც"));
        assertEquals(10, trigrams.size());
    }

    /**
     * Mid-word substring search must work the same way ILIKE '%word%' does
     * in the live Postgres app: any two strings sharing all trigrams of a
     * query word are candidates, and a real (non-coincidental) substring
     * relationship always implies every one of the substring's trigrams is
     * present in the containing text.
     */
    @Test
    void aTrueSubstringsTrigramsAreAllPresentInTheContainingTexts() {
        String containing = "დავაკონფიგურიროთ როუტერი";
        String needle = "კონფიგ";

        Set<String> containingTrigrams = TrigramIndexer.extract(containing);
        Set<String> needleTrigrams = TrigramIndexer.extract(needle);

        assertTrue(containingTrigrams.containsAll(needleTrigrams));
    }

    @Test
    void aNonSubstringIsMissingAtLeastOneTrigram() {
        String text = "საკონფერენციო ოთახი";
        String needle = "კონფიგ";

        Set<String> textTrigrams = TrigramIndexer.extract(text);
        Set<String> needleTrigrams = TrigramIndexer.extract(needle);

        assertFalse(textTrigrams.containsAll(needleTrigrams));
    }
}
